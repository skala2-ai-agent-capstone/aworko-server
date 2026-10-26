package com.aworko.server;

import static org.hamcrest.Matchers.hasItem;
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

    /** 이름이 같은 중첩 record(Item)가 하나의 스키마로 합쳐지지 않는지 */
    @Test
    void nestedItemRecordsKeepTheirOwnSchemas() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.components.schemas.Item").doesNotExist())
                .andExpect(jsonPath("$.components.schemas.LiveFeedbackDisplay.properties.items.items['$ref']")
                        .value("#/components/schemas/LiveFeedbackItem"))
                .andExpect(jsonPath("$.components.schemas.EvaluationReport.properties.items.items['$ref']")
                        .value("#/components/schemas/EvaluationReportItem"))
                .andExpect(jsonPath("$.components.schemas.RubricDetail.properties.items.items['$ref']")
                        .value("#/components/schemas/RubricItem"))
                .andExpect(jsonPath("$.components.schemas.LiveFeedbackItem.properties.state").exists())
                .andExpect(jsonPath("$.components.schemas.EvaluationReportItem.properties.score").exists())
                .andExpect(jsonPath("$.components.schemas.RubricItem.properties.criterion").exists());
    }

    @Test
    void workTypeIsRequiredWhenCreatingAWork() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.components.schemas.CreateWorkRequest.required", hasItem("work_type")))
                .andExpect(jsonPath("$.components.schemas.CreateWorkForm.required", hasItem("work_type")));
    }
}
