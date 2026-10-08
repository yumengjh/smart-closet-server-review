package com.smartcloset.agent;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HelpServiceTest {
    @Test void smartOutfitArticlesDescribeCardsBeforeOpeningWorkspace()throws Exception {
        var help=new HelpService();
        for(String id:java.util.List.of("02-outfits","03-assistant")) {
            String text=help.articles().stream().filter(a->a.id().equals(id)).findFirst().orElseThrow().markdown();
            assertTrue(text.contains("推荐卡片"),id);
            assertTrue(text.contains("「在搭台打开」"),id);
            assertTrue(text.contains("「重新搭配」"),id);
            assertTrue(text.contains("「换一批」"),id);
            assertFalse(text.contains("助手自动打开"),id);
            assertFalse(text.contains("搭配请求会打开"),id);
        }
    }
    @Test void followUpHelpCanBeRetrievedFromCurrentArticles()throws Exception {
        var help=new HelpService();var hits=help.search("重新搭配 换一批");
        assertTrue(hits.stream().anyMatch(h->h.id().equals("02-outfits")));
        assertTrue(hits.stream().anyMatch(h->h.id().equals("03-assistant")));
    }
}
