package com.hsmap.factverification.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsmap.factverification.shared.ServiceException;
import com.hsmap.factverification.shared.VerificationResultValidator;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 验证 Agent 的版本装载、事件映射、主体检查点和统一结果门禁。
 *
 * <p>这些测试不调用真实公司模型或 MCP；真实兼容性在交付阶段单独执行，单元测试保持确定性。
 */
class FactVerificationAgentTest {

    @TempDir
    Path temporaryDirectory;

    /** 运行时根必须是版本父目录，且篡改后的 Skill hash 必须拒绝装载。 */
    @Test
    void loadsFrozenSkillFromVersionParentAndRejectsTampering() throws Exception {
        Path versionRoot = temporaryDirectory.resolve("version-001");
        Path skillRoot = versionRoot.resolve("company-material-fact-check");
        Files.createDirectories(skillRoot);
        Files.writeString(
                skillRoot.resolve("SKILL.md"),
                "---\nname: company-material-fact-check\ndescription: test\n---\n# Test\n");
        FrozenSkillLoader loader = new FrozenSkillLoader();
        String hash = loader.contentHash(versionRoot);

        assertThat(loader.load(versionRoot, hash).getAllSkillNames()).containsExactly("company-material-fact-check");

        Files.writeString(skillRoot.resolve("SKILL.md"), "tampered");
        assertThatThrownBy(() -> loader.load(versionRoot, hash))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("FROZEN_SKILL_HASH_MISMATCH");
    }

    /** AgentScope 细粒度事件映射为浏览器稳定业务事件，不向页面泄漏框架类型。 */
    @Test
    void mapsAgentScopeEventsToBusinessEvents() {
        AgentEventMapper mapper = new AgentEventMapper();

        assertThat(mapper.map(new TextBlockDeltaEvent("reply", "block", "核验中")).type())
                .isEqualTo("TEXT_DELTA");
        assertThat(mapper.map(new ToolCallStartEvent("reply", "call", "resolve_company"))
                        .type())
                .isEqualTo("TOOL_STARTED");
        assertThat(mapper.map(new AgentEndEvent("reply")).type()).isEqualTo("AGENT_ENDED");
    }

    /** 多个主体候选时必须暂停，而不是默认采用第一条继续取证。 */
    @Test
    void pausesWhenCompanyCandidatesAreAmbiguous() {
        CompanyResolutionGate gate = new CompanyResolutionGate();

        CompanyResolution resolution = gate.evaluate(
                "火石",
                java.util.List.of(
                        new CompanyCandidate("C001", "火石科技", 0.91), new CompanyCandidate("C002", "火石信息", 0.90)));

        assertThat(resolution.requiresHumanConfirmation()).isTrue();
        assertThat(resolution.companyId()).isNull();
    }

    /** Agent 输出入库前必须通过 JSON schema，且无证据 VERIFIED 必须失败。 */
    @Test
    void validatesSchemaAndVerifiedEvidenceInvariant() {
        VerificationResultValidator validator =
                new VerificationResultValidator(new ObjectMapper(), "schemas/verification-result.schema.json");
        UUID runId = UUID.randomUUID();
        UUID snapshotId = UUID.randomUUID();
        String invalid =
                """
                {
                  "runId":"%s",
                  "variant":{"type":"SKILL","identifier":"v1","contentHash":"%s"},
                  "documentSnapshotHash":"%s",
                  "evidenceSnapshotId":"%s",
                  "claims":[{
                    "claimId":"c1","claimText":"收入为1000万元",
                    "materialLocator":{"fileId":"f1","lineStart":1,"lineEnd":1},
                    "normalizedClaim":{"metric":"营业收入","period":"2025","operator":"EQUALS","value":1000,"unit":"万元"},
                    "subject":{"companyId":"C001","companyName":"火石科技"},
                    "status":"VERIFIED","riskFlags":[],"evidence":[],
                    "explanation":"缺少证据","requiresHumanIntervention":false
                  }]
                }
                """
                        .formatted(runId, "a".repeat(64), "b".repeat(64), snapshotId);

        assertThatThrownBy(() -> validator.validate(new ObjectMapper().readTree(invalid)))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("RESULT_SCHEMA_INVALID");
    }
}
