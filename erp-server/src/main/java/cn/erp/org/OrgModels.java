package cn.erp.org;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

final class OrgModels {
    private OrgModels() {}
    record DepartmentInput(
            @NotBlank(message="请输入部门编码") @Pattern(regexp="[A-Za-z0-9_-]{1,32}",message="部门编码限32位字母、数字、下划线或短横线") String code,
            @NotBlank(message="请输入部门名称") @Size(max=100,message="部门名称最多100字") String name,
            @PositiveOrZero(message="上级部门编号无效") Long parentId,
            @Positive(message="负责人编号无效") Long leaderUserId,
            @NotNull(message="请输入排序") @Min(value=0,message="排序不能为负") @Max(value=1000000,message="排序过大") Integer sortNo,
            @PositiveOrZero(message="版本号无效") Long version) {}
    record PostInput(
            @NotBlank(message="请输入岗位编码") @Pattern(regexp="[A-Za-z0-9_-]{1,32}",message="岗位编码限32位字母、数字、下划线或短横线") String code,
            @NotBlank(message="请输入岗位名称") @Size(max=100,message="岗位名称最多100字") String name,
            @NotNull(message="请输入排序") @Min(value=0,message="排序不能为负") @Max(value=1000000,message="排序过大") Integer sortNo,
            @PositiveOrZero(message="版本号无效") Long version) {}
    record Version(@NotNull(message="请提供版本号") @PositiveOrZero(message="版本号无效") Long version) {}
    record Move(@PositiveOrZero(message="上级部门编号无效") Long parentId,
            @Positive(message="排序参照部门编号无效") Long beforeId,
            @NotNull(message="请提供版本号") @PositiveOrZero(message="版本号无效") Long version) {}
    record Membership(@NotNull(message="请选择部门") @Positive(message="部门编号无效") Long id,
            @NotNull(message="请选择主部门标记") Boolean primary) {}
    record Assignment(
            @NotNull(message="请提供部门列表") @Size(max=100,message="最多分配100个部门") List<@NotNull(message="部门不能为空") @Valid Membership> departments,
            @NotNull(message="请提供岗位列表") @Size(max=100,message="最多分配100个岗位") List<@NotNull(message="岗位不能为空") @Positive(message="岗位编号无效") Long> postIds,
            @NotNull(message="请提供组织配置版本") @PositiveOrZero(message="版本号无效") Long version) {}
    record Department(String id,String parentId,String code,String name,String leaderUserId,String leaderName,
            int sortNo,String path,int depth,String status,long version,long memberCount) {}
    record Post(String id,String code,String name,int sortNo,String status,long version,long memberCount) {}
    record User(String id,String username,String displayName,String status) {}
    record AssignmentOptions(List<Department> departments,List<Post> posts) {}
    record UserOrganization(String userId,String companyId,long version,List<MembershipView> departments,List<String> postIds) {}
    record MembershipView(String id,boolean primary) {}
    record Page<T>(List<T> items,String nextCursor) {}
}
