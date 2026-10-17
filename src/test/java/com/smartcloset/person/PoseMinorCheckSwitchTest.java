package com.smartcloset.person;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.smartcloset.image.ProcessingException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The minor check of pose extraction is off by default: a stray MINOR_LIKELY on an accepted picture no longer blocks it. */
class PoseMinorCheckSwitchTest {
    static final String FLAGGED = """
        {"accepted":true,"rejectReason":"MINOR_LIKELY","name":"单手叉腰","description":"身体正对镜头，右手叉腰，左臂自然下垂，双脚与肩同宽站立。","tags":["站姿"],"viewAngle":"FRONT","joints":[]}""";
    final JsonMapper json = JsonMapper.builder().build();

    @Test void checkOffLetsFlaggedPictureThrough() {
        var poses = new PoseCards(null, json, png -> null, false);
        assertEquals("单手叉腰", poses.normalize(json.readTree(FLAGGED)).name());
    }

    @Test void checkOnStillRefuses() {
        var poses = new PoseCards(null, json, png -> null, true);
        assertEquals("POSE_REJECTED", assertThrows(ProcessingException.class, () -> poses.normalize(json.readTree(FLAGGED))).code());
    }
}
