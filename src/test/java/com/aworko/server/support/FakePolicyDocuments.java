package com.aworko.server.support;

import com.aworko.server.settings.PolicyDocumentGateway;
import com.aworko.server.settings.RubricGateway;
import com.aworko.server.settings.dto.PolicyDocumentSaved;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * business-mcp /internal/documents 대역. 받은 업로드를 기억하고, 정해 둔 결과(기본: 새 현행 버전)나 MCP 오류를 돌려준다.
 * 조항 분할·색인은 MCP 쪽 책임이라 DB에는 아무것도 쓰지 않는다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class FakePolicyDocuments {

    @Bean
    @Primary
    Recorder fakePolicyDocumentGateway() {
        return new Recorder();
    }

    public static class Recorder implements PolicyDocumentGateway {

        public final List<PolicyUpload> uploads = new CopyOnWriteArrayList<>();
        private RubricGateway.Rejected nextError;
        private boolean nextDuplicate;

        public void reset() {
            uploads.clear();
            nextError = null;
            nextDuplicate = false;
        }

        public void rejectNext(String code, String message) {
            nextError = new RubricGateway.Rejected(code, message);
        }

        /** 다음 업로드를 '같은 버전·같은 내용'으로 돌려준다 */
        public void duplicateNext() {
            nextDuplicate = true;
        }

        @Override
        public PolicyDocumentSaved upload(PolicyUpload upload) {
            uploads.add(upload);
            if (nextError != null) {
                RubricGateway.Rejected e = nextError;
                nextError = null;
                throw e;
            }
            boolean duplicate = nextDuplicate;
            nextDuplicate = false;
            return new PolicyDocumentSaved("POL-SALES", upload.version(), "영업 업무 내규", "현행", 48, 21,
                    duplicate ? List.of() : List.of("POL-SALES@v1.1"),
                    duplicate ? null : new PolicyDocumentSaved.Diff("POL-SALES@v1.1", List.of("ART-20"), List.of(),
                            List.of("ART-04")),
                    duplicate, true);
        }
    }
}
