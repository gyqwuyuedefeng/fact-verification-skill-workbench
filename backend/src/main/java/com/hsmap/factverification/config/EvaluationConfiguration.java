package com.hsmap.factverification.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsmap.factverification.evaluation.EvaluationExecutionPort;
import com.hsmap.factverification.evaluation.EvaluationRunner;
import com.hsmap.factverification.evaluation.dataset.GoldDatasetLoader;
import com.hsmap.factverification.evaluation.manifest.RunManifestFactory;
import com.hsmap.factverification.evaluation.report.EvaluationReportGenerator;
import com.hsmap.factverification.evaluation.scoring.GoldScorer;
import com.hsmap.factverification.shared.CanonicalJsonHasher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 组装同条件评测的纯业务组件，不引入调度平台或消息队列。 */
@Configuration
public class EvaluationConfiguration {

    @Bean
    GoldDatasetLoader goldDatasetLoader(ObjectMapper objectMapper, CanonicalJsonHasher hasher) {
        return new GoldDatasetLoader(objectMapper, hasher);
    }

    @Bean
    RunManifestFactory runManifestFactory(CanonicalJsonHasher hasher) {
        return new RunManifestFactory(hasher);
    }

    @Bean
    EvaluationRunner evaluationRunner(EvaluationExecutionPort executor) {
        return new EvaluationRunner(executor, new GoldScorer());
    }

    @Bean
    EvaluationReportGenerator evaluationReportGenerator(ObjectMapper objectMapper) {
        return new EvaluationReportGenerator(objectMapper);
    }
}
