package com.aworko.server.evaluation;

import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/** evaluation_drafts·evaluation_coaching의 복합 키 (evaluation_id, revision). */
@EqualsAndHashCode
@NoArgsConstructor
@AllArgsConstructor
public class RevisionKey implements Serializable {
    private String evaluationId;
    private int revision;
}
