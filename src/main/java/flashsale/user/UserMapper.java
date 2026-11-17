package flashsale.user;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserMapper {
    @Select("SELECT id FROM t_user WHERE phone = #{phone}")
    Long findIdByPhone(String phone);
}
