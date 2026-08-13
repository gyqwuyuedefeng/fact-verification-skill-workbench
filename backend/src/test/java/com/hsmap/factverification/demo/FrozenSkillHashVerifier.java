package com.hsmap.factverification.demo;

import com.hsmap.factverification.agent.FrozenSkillLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Task 8 专用的只读冻结目录核验器。
 *
 * <p>该类只位于 test classpath，不新增管理员 HTTP 能力。数据库仍由只读查询导出 UUID、version 和 content_hash；目录 hash
 * 则必须调用生产 {@link FrozenSkillLoader#contentHash(Path)}，从而与真正装载 Skill 的路径排序和字节边界完全一致。
 */
final class FrozenSkillHashVerifier {

    private static final Pattern CONTENT_HASH = Pattern.compile("[0-9a-f]{64}");

    private FrozenSkillHashVerifier() {}

    /**
     * 逐行验证 TSV 中的冻结版本。
     *
     * @param storageRoot 当前应用实际使用的 storageRoot
     * @param expectationsTsv 只读数据库查询产生的三列 TSV：id、version、content_hash
     * @return 已通过 snapshot/runtime/数据库 hash 与 version 后缀四重校验的结果
     * @throws IOException 清单或冻结文件不可读
     * @throws IllegalArgumentException 清单格式、UUID 或 hash 不符合固定契约
     * @throws IllegalStateException 任一冻结版本的实际内容与数据库登记不一致
     */
    static List<VerifiedVersion> verify(Path storageRoot, Path expectationsTsv) throws IOException {
        List<VerifiedVersion> verified = new ArrayList<>();
        FrozenSkillLoader loader = new FrozenSkillLoader();
        int lineNumber = 0;
        for (String line : Files.readAllLines(expectationsTsv)) {
            lineNumber++;
            if (line.isBlank()) {
                continue;
            }
            String[] fields = line.split("\\t", -1);
            if (fields.length != 3) {
                throw new IllegalArgumentException("冻结版本清单第 " + lineNumber + " 行必须恰好包含三列");
            }
            UUID versionId;
            try {
                versionId = UUID.fromString(fields[0]);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("冻结版本清单第 " + lineNumber + " 行 UUID 无效", exception);
            }
            if (!versionId.toString().equals(fields[0])) {
                throw new IllegalArgumentException("冻结版本清单第 " + lineNumber + " 行 UUID 不是 canonical 形式");
            }
            String version = fields[1];
            String expectedHash = fields[2];
            if (!CONTENT_HASH.matcher(expectedHash).matches()) {
                throw new IllegalArgumentException("冻结版本清单第 " + lineNumber + " 行 content_hash 无效");
            }
            if (!version.endsWith(expectedHash.substring(0, 12))) {
                throw new IllegalStateException("冻结版本 " + versionId + " 的 version 不含数据库 hash 十二位后缀");
            }

            Path snapshotRoot = storageRoot.resolve("skill-snapshots").resolve(versionId.toString());
            Path runtimeRoot = storageRoot.resolve("skill-runtime").resolve(versionId.toString());
            String snapshotHash = loader.contentHash(snapshotRoot);
            String runtimeHash = loader.contentHash(runtimeRoot);
            if (!expectedHash.equals(snapshotHash) || !expectedHash.equals(runtimeHash)) {
                throw new IllegalStateException("冻结版本 " + versionId + " 的目录 hash 与数据库 content_hash 不一致");
            }
            verified.add(new VerifiedVersion(versionId, version, snapshotHash, runtimeHash));
        }
        if (verified.isEmpty()) {
            throw new IllegalArgumentException("冻结版本清单不能为空");
        }
        return List.copyOf(verified);
    }

    /** 记录已由生产 hash 实现验证通过的单个冻结版本，供 Maven 验收日志逐行留证。 */
    record VerifiedVersion(UUID versionId, String version, String snapshotHash, String runtimeHash) {}
}
