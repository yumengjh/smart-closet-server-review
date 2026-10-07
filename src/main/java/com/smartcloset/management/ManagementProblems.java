package com.smartcloset.management;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/** Builds RFC 9457 bodies with the contract's extra members: code, requestId, violations. */
public final class ManagementProblems {
    public static final String REQUEST_ID_ATTRIBUTE = ManagementProblems.class.getName() + ".requestId";
    private ManagementProblems() {}

    public static ProblemDetail problem(HttpStatus status, String code, String title, String detail, String requestId) {
        var problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("urn:closet:problem:" + code.toLowerCase(Locale.ROOT).replace('_', '-')));
        problem.setTitle(title);
        problem.setProperty("code", code);
        if (requestId != null) problem.setProperty("requestId", requestId);
        return problem;
    }

    public static ProblemDetail withViolations(ProblemDetail problem, List<Map<String, String>> violations) {
        if (!violations.isEmpty()) problem.setProperty("violations", violations);
        return problem;
    }
}
