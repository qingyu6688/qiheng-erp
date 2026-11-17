package cn.erp.crm;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

final class CrmCoreModels {
    private CrmCoreModels() {}

    record ClueInput(
            @NotBlank(message = "请输入线索名称") @Size(max = 100, message = "线索名称最多 100 字") String name,
            @Size(max = 50, message = "联系人姓名最多 50 字") String contactName,
            @Size(max = 32, message = "手机号最多 32 字") String mobile,
            @Email(message = "邮箱格式不正确") @Size(max = 100, message = "邮箱最多 100 字") String email,
            @Size(max = 32, message = "税号最多 32 字") String taxNo,
            @Size(max = 64, message = "来源编码过长") String source,
            @Size(max = 64, message = "行业编码过长") String industry,
            @Positive(message = "区划无效") Long areaId,
            Instant nextContactAt, @PositiveOrZero(message = "版本号无效") Long version) {}

    record ClueAction(
            @NotNull(message = "请提供版本号") @PositiveOrZero(message = "版本号无效") Long version,
            @Positive(message = "员工标识无效") Long ownerUserId,
            @Positive(message = "客户标识无效") Long partnerId,
            @Size(max = 32, message = "主数据编码最多 32 字") String code) {}

    record ClueReference(@NotNull(message = "请提供线索标识") @Positive(message = "线索标识无效") Long id,
                         @NotNull(message = "请提供版本号") @PositiveOrZero(message = "版本号无效") Long version) {}

    record BatchAssign(@NotNull(message = "请选择员工") @Positive(message = "员工标识无效") Long ownerUserId,
                       @NotNull(message = "请选择线索") @Size(min = 1, max = 100, message = "每批选择 1 到 100 条线索")
                       List<@NotNull(message = "不能选择空线索") @Valid ClueReference> items) {}

    record CustomerInput(
            @Positive(message = "客户标识无效") Long partnerId,
            @Positive(message = "负责人标识无效") Long ownerUserId,
            @Size(max = 64, message = "客户级别编码过长") String level,
            @Size(max = 64, message = "来源编码过长") String source,
            @Size(max = 64, message = "行业编码过长") String industry,
            @Positive(message = "区划无效") Long areaId,
            @Pattern(regexp = "sold|unsold", message = "成交状态无效") String dealStatus,
            Instant nextContactAt, @PositiveOrZero(message = "版本号无效") Long version) {}

    record OwnerAction(
            @PositiveOrZero(message = "版本号无效") Long version,
            @Positive(message = "员工标识无效") Long ownerUserId) {}

    record TeamInput(
            @NotNull(message = "请选择团队成员") @Positive(message = "员工标识无效") Long userId,
            @NotBlank(message = "请选择团队权限") @Pattern(regexp = "read|write", message = "团队权限无效") String permission,
            Instant expiresAt) {}

    record ContactInput(
            @Positive(message = "联系人标识无效") Long contactId,
            @NotBlank(message = "请输入联系人姓名") @Size(max = 50, message = "姓名最多 50 字") String name,
            @Size(max = 32, message = "电话最多 32 字") String mobile,
            @Email(message = "邮箱格式不正确") @Size(max = 100, message = "邮箱最多 100 字") String email,
            @Size(max = 200, message = "地址最多 200 字") String address,
            boolean isDefault, @PositiveOrZero(message = "联系人版本号无效") Long version,
            @Positive(message = "区划无效") Long areaId,
            @Size(max = 80, message = "职位最多 80 字") String position,
            boolean decisionMaker, LocalDate birthday,
            @Size(max = 80, message = "微信号最多 80 字") String wechat,
            @Size(max = 500, message = "备注最多 500 字") String remark,
            @PositiveOrZero(message = "联系人补充资料版本号无效") Long profileVersion) {}

    record PoolRuleInput(boolean enabled,
                         @Min(value = 1, message = "未跟进天数至少为 1") @Max(value = 3650, message = "未跟进天数最多 3650") int noFollowDays,
                         @Min(value = 1, message = "未成交天数至少为 1") @Max(value = 3650, message = "未成交天数最多 3650") int noDealDays,
                         @PositiveOrZero(message = "提醒天数不能为负数") int notifyBeforeDays,
                         @Size(max = 50, message = "回收客户级别最多 50 项") List<@NotBlank(message = "客户级别不能为空") String> applyToLevels,
                         @PositiveOrZero(message = "版本号无效") Long version) {}

    record OwnerLimitInput(
            @NotBlank(message = "请选择限制类型") @Pattern(regexp = "user|department", message = "限制类型无效") String targetType,
            @NotNull(message = "请选择限制对象") @Positive(message = "限制对象无效") Long targetId,
            @PositiveOrZero(message = "拥有数量不能为负数") int maxOwned,
            @PositiveOrZero(message = "锁定数量不能为负数") int maxLocked,
            boolean includeSold, @PositiveOrZero(message = "版本号无效") Long version) {}

    record FollowInput(
            @NotBlank(message = "请选择跟进对象") @Pattern(regexp = "clue|customer|business|contract", message = "跟进对象无效") String bizType,
            @NotNull(message = "请提供跟进对象") @Positive(message = "跟进标识无效") Long bizId,
            @NotBlank(message = "请选择跟进方式") @Size(max = 64, message = "跟进方式编码过长") String method,
            @NotBlank(message = "请输入跟进内容") @Size(max = 5000, message = "跟进内容最多 5000 字") String content,
            Instant nextContactAt,
            @Size(max = 20, message = "每次跟进最多提及 20 位同事") List<@NotNull(message = "员工标识不能为空") @Positive(message = "员工标识无效") Long> mentionedUserIds) {}
}
