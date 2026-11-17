package flashsale.voucher;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface VoucherMapper {
    @Select("SELECT stock FROM t_voucher WHERE id = #{voucherId}")
    Integer selectStock(long voucherId);

    /** 错误写法用：直接把在 Java 里算好的库存写回去 */
    @Update("UPDATE t_voucher SET stock = #{stock} WHERE id = #{voucherId}")
    int updateStock(long voucherId, int stock);

    /** 正确写法用：判断和扣减在一条语句里，按影响行数判断是否扣成功 */
    @Update("UPDATE t_voucher SET stock = stock - 1 WHERE id = #{voucherId} AND stock > 0")
    int deductStock(long voucherId);

    /** 错误写法用：查演示表里这个用户买过没有 */
    @Select("SELECT COUNT(*) FROM t_voucher_order_naive WHERE user_id = #{userId} AND voucher_id = #{voucherId}")
    int countNaiveOrder(long userId, long voucherId);

    /** 错误写法用：演示表没有唯一索引，重复的订单也能插进去 */
    @Insert("INSERT INTO t_voucher_order_naive (id, user_id, voucher_id) VALUES (#{orderId}, #{userId}, #{voucherId})")
    int insertNaiveOrder(long orderId, long userId, long voucherId);

    @Insert("INSERT INTO t_voucher_order (id, user_id, voucher_id) VALUES (#{orderId}, #{userId}, #{voucherId})")
    int insertOrder(long orderId, long userId, long voucherId);
}
