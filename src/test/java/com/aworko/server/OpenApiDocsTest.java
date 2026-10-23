package com.aworko.server;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aworko.server.support.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/** Swagger 명세가 실제 요청·응답과 같은 snake_case이고, multipart 폼 칸이 보이는지 */
@SpringBootTest(properties = "app.messaging.outbox.enabled=false")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OpenApiDocsTest {

    @Autowired MockMvc mvc;

    @Test
    void schemasUseWireNamesAndMultipartFormsListTheirFields() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.components.schemas.LoginRequest.properties.employee_no").exists())
                .andExpect(jsonPath("$.components.schemas.WorkSummary.properties.work_id").exists())
                .andExpect(jsonPath("$.components.schemas.WorkSummary.properties.workId").doesNotExist())
                .andExpect(jsonPath("$.components.schemas.CreateWorkForm.properties.work_type").exists())
                .andExpect(jsonPath("$.components.schemas.CreateWorkForm.properties.files").exists())
                .andExpect(jsonPath("$.components.schemas.SourceDocumentForm.properties.doc_type").exists())
                .andExpect(jsonPath("$.paths['/api/works'].post.requestBody.content['multipart/form-data']").exists())
                .andExpect(jsonPath("$.paths['/api/works'].post.requestBody.content['application/json']").exists())
                .andExpect(jsonPath("$.paths['/api/works'].post.parameters").doesNotExist());
    }
}
