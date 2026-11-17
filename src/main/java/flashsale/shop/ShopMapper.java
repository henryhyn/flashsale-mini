package flashsale.shop;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ShopMapper {
    /**
     * 按 id 查商户。真实的商户详情要联表查评分、套餐、营业时间，比主键查询慢得多；
     * 这里用 SLEEP 模拟这段耗时 (秒数由实验设置，默认 0)，并且它和真实查询一样占着一个数据库连接。
     */
    @Select("""
            SELECT s.id, s.name, s.address, s.avg_price, s.score
            FROM t_shop s JOIN (SELECT SLEEP(#{delaySeconds})) heavy
            WHERE s.id = #{id}""")
    Shop findById(long id, double delaySeconds);

    @Update("UPDATE t_shop SET avg_price = #{avgPrice} WHERE id = #{id}")
    int updateAvgPrice(long id, int avgPrice);
}
