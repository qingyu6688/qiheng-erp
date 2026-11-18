package cn.erp.identity;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
interface UserAccessMapper {
    String ROLE_PERMISSION_JOINS = """
            JOIN sys_role r ON r.tenant_id = ur.tenant_id AND r.id = ur.role_id AND r.status = 'active'
            JOIN sys_role_permission rp ON rp.tenant_id = r.tenant_id AND rp.role_id = r.id
            JOIN sys_permission p ON p.id = rp.permission_id
            """;
    String ROLE_PERMISSIONS = "FROM sys_user_role ur " + ROLE_PERMISSION_JOINS;

    // 管理事务会用 JDBC 修改角色关系，权限读取必须即时反映同事务的变更。
    @Options(useCache=false, flushCache=Options.FlushCachePolicy.TRUE)
    @Select("SELECT DISTINCT p.code " + ROLE_PERMISSIONS + """
            WHERE ur.tenant_id = #{tenant} AND ur.user_id = #{user} ORDER BY p.code
            """)
    List<String> permissions(@Param("tenant") long tenant, @Param("user") long user);
}
