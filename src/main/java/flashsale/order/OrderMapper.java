package flashsale.order;

import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface OrderMapper {
    @Insert("INSERT INTO t_voucher_order (id, user_id, voucher_id) VALUES (#{orderId}, #{userId}, #{voucherId})")
    int insert(long orderId, long userId, long voucherId);

    @Select("SELECT id, user_id, voucher_id, status FROM t_voucher_order WHERE id = #{orderId}")
    VoucherOrder find(long orderId);

    @Select("SELECT id FROM t_voucher_order WHERE voucher_id = #{voucherId}")
    List<Long> findIds(long voucherId);

    /** 状态只能从 from 变成 to：支付和超时取消并发时，只有一个能成功 */
    @Update("UPDATE t_voucher_order SET status = #{to} WHERE id = #{orderId} AND status = #{from}")
    int changeStatus(long orderId, int from, int to);

    @Update("UPDATE t_voucher SET stock = stock - 1 WHERE id = #{voucherId} AND stock > 0")
    int deductStock(long voucherId);

    @Update("UPDATE t_voucher SET stock = stock + 1 WHERE id = #{voucherId}")
    int restoreStock(long voucherId);

    @Insert("INSERT INTO t_voucher_order_naive (id, user_id, voucher_id) VALUES (#{orderId}, #{userId}, #{voucherId})")
    int insertNaive(long orderId, long userId, long voucherId);
}
