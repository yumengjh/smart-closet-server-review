package com.smartcloset.agent;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class AgentIntentPolicyTest {
    @Test void readonlyTurnsCannotAcquireMutationCapabilities(){for(String text:new String[]{"我有多少件衣服？","帮我看看我的搭配有几套","帮我查一下我的搭配","我有多少裙子、衬衫和裤子？","你好","你会修改衣物吗？","不要给我搭配，只统计夏季衣服","他问我：\"帮我搭配三套\"，是什么意思？"}){var turn=AgentIntentPolicy.classify(text);assertThrows(com.smartcloset.image.ProcessingException.class,()->AgentIntentPolicy.require(turn,"build_outfits"),text);assertThrows(com.smartcloset.image.ProcessingException.class,()->AgentIntentPolicy.require(turn,"prepare_command"),text);}}
    @Test void explicitOutfitAndIndependentMultiIntentAreAllowed(){assertTrue(AgentIntentPolicy.classify("用这件衣物组合三套").tools().contains("propose_outfits"));assertTrue(AgentIntentPolicy.classify("帮我搭配一套通勤穿的").tools().containsAll(java.util.Set.of("recommend_outfits","present_outfits")));assertFalse(AgentIntentPolicy.classify("我有多少件衣服？").tools().contains("recommend_outfits"));assertTrue(AgentIntentPolicy.classify("帮我搭配").purposes().contains("OUTFIT"));var multi=AgentIntentPolicy.classify("我有多少衬衫，顺便把这件季节改成夏季");assertTrue(multi.tools().contains("closet_summary"));assertTrue(multi.tools().contains("prepare_command"));}
    @Test void recommendationFollowUpsAreOutfitRequests(){
        for(String text:new String[]{"重新搭配","那就重新搭配啊","重新推荐","重新来","换一批","换几套","换一套","再推荐","再搭","再来几套","都不喜欢","还有别的吗","不要那双，再搭一套","不要那件衬衫，重新搭配"})
            assertTrue(AgentIntentPolicy.classify(text).tools().contains("recommend_outfits"),text);
    }
    @Test void shortFollowUpsNeedRecommendationContext(){
        for(String text:new String[]{"重新","换","再试试","别的","不喜欢","不要那双"}){
            assertFalse(AgentIntentPolicy.classify(text,false).tools().contains("recommend_outfits"),text);
            assertTrue(AgentIntentPolicy.classify(text,true).tools().containsAll(java.util.Set.of("recommend_outfits","present_outfits")),text);
        }
        assertFalse(AgentIntentPolicy.classify("你好",true).tools().contains("recommend_outfits"));
        assertFalse(AgentIntentPolicy.classify("别的"+"说明".repeat(40),true).tools().contains("recommend_outfits"));
        assertFalse(AgentIntentPolicy.classify("他说：“换”，是什么意思？",true).tools().contains("recommend_outfits"));
    }
    @Test void refusalAndAbilityQuestionsStillDoNotAuthorizeOutfits(){
        for(String text:new String[]{"不要换一批","不想换一套","不要再穿那双鞋，也不要给我搭配","不要给我搭配","别给我重新推荐","不需要搭配","不用重新搭配","不要再推荐","不能搭配","禁止自动搭配","我不想换一批，禁止推荐","不要帮我准备穿搭","怎么重新搭配","怎么搭配","如何重新搭配","你有什么功能？"})
            for(boolean context:new boolean[]{false,true})assertFalse(AgentIntentPolicy.classify(text,context).tools().contains("recommend_outfits"),text);
        // Without a recommendation on screen these are questions about the capability; right after one they are requests.
        for(String text:new String[]{"你会重新搭配吗？","能不能重新推荐？","能不能换一批？"}){
            assertFalse(AgentIntentPolicy.classify(text,false).tools().contains("recommend_outfits"),text);
            assertTrue(AgentIntentPolicy.classify(text,true).tools().contains("recommend_outfits"),text);
        }
        assertTrue(AgentIntentPolicy.classify("能不能重新搭配",false).tools().contains("recommend_outfits"));
    }
    @Test void everydayOutfitRequestsAndCorrectionsAfterACard(){
        for(String text:new String[]{"我是女生 帮我发一套比较可爱的穿搭","来一套通勤穿搭","给我整一套约会的","想要一套甜美的穿搭","今天穿什么"})
            assertTrue(AgentIntentPolicy.classify(text).tools().contains("recommend_outfits"),text);
        assertTrue(AgentIntentPolicy.classify("你来搭配",true).tools().contains("recommend_outfits"));
        for(String text:new String[]{"不是有更好看的裙子吗","要可爱一点的","换成裙子"}){
            assertTrue(AgentIntentPolicy.classify(text,true).tools().contains("recommend_outfits"),text);
            assertFalse(AgentIntentPolicy.classify(text,false).tools().contains("recommend_outfits"),text);
        }
        assertFalse(AgentIntentPolicy.classify("我有几套搭配？").tools().contains("recommend_outfits"));
    }
}
