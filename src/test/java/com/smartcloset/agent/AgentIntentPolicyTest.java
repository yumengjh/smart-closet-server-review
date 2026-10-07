package com.smartcloset.agent;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class AgentIntentPolicyTest {
    @Test void readonlyTurnsCannotAcquireMutationCapabilities(){for(String text:new String[]{"我有多少件衣服？","帮我看看我的搭配有几套","帮我查一下我的搭配","我有多少裙子、衬衫和裤子？","你好","你会修改衣物吗？","不要给我搭配，只统计夏季衣服","他问我：\"帮我搭配三套\"，是什么意思？"}){var turn=AgentIntentPolicy.classify(text);assertThrows(com.smartcloset.image.ProcessingException.class,()->AgentIntentPolicy.require(turn,"build_outfits"),text);assertThrows(com.smartcloset.image.ProcessingException.class,()->AgentIntentPolicy.require(turn,"prepare_command"),text);}}
    @Test void explicitOutfitAndIndependentMultiIntentAreAllowed(){assertTrue(AgentIntentPolicy.classify("用这件衣物组合三套").tools().contains("propose_outfits"));assertTrue(AgentIntentPolicy.classify("帮我搭配").purposes().contains("OUTFIT"));var multi=AgentIntentPolicy.classify("我有多少衬衫，顺便把这件季节改成夏季");assertTrue(multi.tools().contains("closet_summary"));assertTrue(multi.tools().contains("prepare_command"));}
}
