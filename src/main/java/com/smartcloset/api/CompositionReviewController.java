package com.smartcloset.api;
import com.smartcloset.agent.CompositionReviews;
import com.smartcloset.security.ClosetIdentity;
import org.springframework.web.bind.annotation.*;
@RestController
public class CompositionReviewController {
    private final CompositionReviews reviews;
    public CompositionReviewController(CompositionReviews reviews){this.reviews=reviews;}
    @PostMapping("/api/outfit-reviews") public Object review(@RequestBody CompositionReviews.Request request){return reviews.review(ClosetIdentity.username(),request);}
}
