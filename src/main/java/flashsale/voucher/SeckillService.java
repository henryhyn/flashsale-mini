package flashsale.voucher;

import flashsale.common.BizException;
import flashsale.common.IdGenerator;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 落库：m1 的同步版秒杀直接调用；m3 的几种写法先在 Redis 或锁里判断资格，再调用这里 */
@Service
public class SeckillService {
    private final VoucherMapper mapper;
    private final IdGenerator ids;

    public SeckillService(VoucherMapper mapper, IdGenerator ids) {
        this.mapper = mapper;
        this.ids = ids;
    }

    /**
     * 错误写法 (很多教程的第一版)：先查库存和是否买过，在 Java 里判断，再写回。
     * 查询是快照读，不加锁，并发请求会读到同一个库存、都判断“没买过”。
     * 订单写进没有唯一索引的演示表，好让重复下单显现出来。
     */
    @Transactional
    public long naive(long userId, long voucherId) {
        Integer stock = mapper.selectStock(voucherId);
        if (stock == null) throw new BizException(404, "优惠券不存在");
        if (stock <= 0) throw new BizException(1001, "已抢光");
        if (mapper.countNaiveOrder(userId, voucherId) > 0) throw new BizException(1002, "不能重复下单");
        mapper.updateStock(voucherId, stock - 1);
        long orderId = ids.nextId();
        mapper.insertNaiveOrder(orderId, userId, voucherId);
        return orderId;
    }

    /**
     * 正确写法：条件更新扣库存 (当前读 + 行锁)，(user_id, voucher_id) 唯一索引兜底一人一单。
     * 重复下单时插入失败，抛出的异常让整个事务回滚，刚扣的库存也一起恢复。
     */
    @Transactional
    public long safe(long userId, long voucherId) {
        if (mapper.deductStock(voucherId) == 0) throw new BizException(1001, "已抢光");
        long orderId = ids.nextId();
        try {
            mapper.insertOrder(orderId, userId, voucherId);
        } catch (DuplicateKeyException e) {
            throw new BizException(1002, "不能重复下单");
        }
        return orderId;
    }

    /**
     * 加锁的写法用 (m3)：在锁里查是否买过、扣库存、写订单。订单写进没有唯一索引的演示表，
     * 一人一单完全靠外面的锁，这样才能看出锁有没有起作用。
     * 锁必须加在事务外面：先释放锁、后提交事务的话，下一个请求会读不到这条还没提交的订单。
     */
    @Transactional
    public long checkThenCreateNaive(long userId, long voucherId) {
        if (mapper.countNaiveOrder(userId, voucherId) > 0) throw new BizException(1002, "不能重复下单");
        if (mapper.deductStock(voucherId) == 0) throw new BizException(1001, "已抢光");
        long orderId = ids.nextId();
        mapper.insertNaiveOrder(orderId, userId, voucherId);
        return orderId;
    }

    /** Lua 写法的对照实验用 (m3)：资格已经在 Redis 里判断过，这里只扣库存、写演示表 */
    @Transactional
    public long createNaive(long userId, long voucherId) {
        if (mapper.deductStock(voucherId) == 0) throw new BizException(1001, "已抢光");
        long orderId = ids.nextId();
        mapper.insertNaiveOrder(orderId, userId, voucherId);
        return orderId;
    }
}
