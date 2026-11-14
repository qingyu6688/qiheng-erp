package cn.erp.masterdata;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;

final class WarehouseModels {
    private WarehouseModels() {}

    record Create(
            @NotBlank(message = "请输入仓库编码") @Size(max = 32, message = "仓库编码最多 32 个字符") String code,
            @NotBlank(message = "请输入仓库名称") @Size(max = 100, message = "仓库名称最多 100 个字符") String name,
            @Size(max = 200, message = "仓库地址最多 200 个字符") String address) {
        Create {
            code = clean(code);
            name = clean(name);
            address = clean(address);
        }
    }

    record Edit(
            @NotBlank(message = "请输入仓库编码") @Size(max = 32, message = "仓库编码最多 32 个字符") String code,
            @NotBlank(message = "请输入仓库名称") @Size(max = 100, message = "仓库名称最多 100 个字符") String name,
            @Size(max = 200, message = "仓库地址最多 200 个字符") String address,
            @NotNull(message = "请提供版本号") @PositiveOrZero(message = "版本号不能为负数") Long version) {
        Edit {
            code = clean(code);
            name = clean(name);
            address = clean(address);
        }
    }

    record Version(@NotNull(message = "请提供版本号") @PositiveOrZero(message = "版本号不能为负数") Long version) {}

    record View(String id, String code, String name, String address, String status,
                long version, List<String> allowedActions) {}

    record Page(List<View> items, String nextCursor, boolean hasMore, Integer total, Boolean totalExact) {}

    record Result(String status, View response) {}

    static String clean(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }
}
