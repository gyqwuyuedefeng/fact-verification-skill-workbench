package com.hsmap.factverification.skill;

import java.nio.file.Path;

/** 冻结后可审核快照和 AgentScope 只读装载根。 */
public record FrozenSkillSnapshot(String contentHash, Path snapshotRoot, Path runtimeRoot) {}
