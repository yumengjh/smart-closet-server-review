package com.smartcloset.agent;

import java.util.*;
import java.util.regex.Pattern;
import com.smartcloset.image.ProcessingException;
import org.springframework.http.HttpStatus;

/** Conservative user-expression gate. Model routing can narrow, never enlarge this capability set.
 * Actual writes additionally pass owner, command, confirmation and revision checks. */
public final class AgentIntentPolicy {
    private static final Set<String> READ=Set.of("search_help","search_wardrobe","closet_summary","get_garment","get_outfit","list_outfits","list_tasks","review_outfit","get_personal_status");
    public record Turn(Set<String> tools,Set<String> purposes) {}
    private static boolean has(String text,String pattern){return Pattern.compile(pattern,Pattern.CASE_INSENSITIVE).matcher(text).find();}
    public static Turn classify(String text) { return classify(text,false); }
    public static Turn classify(String text,boolean followsRecommendation) {
        var tools=new HashSet<>(READ);var purposes=new HashSet<String>();
        // Quoted examples, negated instructions and ability questions never become write authority.
        String direct=text.replaceAll("[“\"「『][^”\"」』]*[”\"」』]","");
        boolean negative=has(direct,"不要|别给|不需要|不想|不能|禁止|不要自动|不用|不允许");
        boolean ability=has(direct,"会不会|能不能.{0,8}(吗|么|？|\\?)$|你会.{0,12}(吗|么)|能做什么|有什么功能|怎么(重新)?(修改|删除|搭配|生成|推荐)|如何");
        // Right after a recommendation card, "能不能重新搭配？/你能换一批吗" is a polite request, not a capability question.
        boolean politeFollowUp=followsRecommendation&&!has(direct,"怎么|如何|什么功能|能做什么")&&has(direct,"重新|换|再|别的");
        if(politeFollowUp)ability=false;
        boolean outfitNegative=has(direct,"(不要|别|不需要|不想|(?<!能)不能|禁止|不用|不允许)(再|自动|给我|帮我|为我|给|帮|重新|继续|进行|做|任何|你|我|\\s)*(搭配|推荐|搭建|组合|准备穿搭|换一批|换几套|换一套|来)|不喜欢.{0,8}(搭配|推荐).{0,8}(功能|服务)");
        if(!outfitNegative&&!ability) {
            boolean outfit=has(direct,"(帮我|给我|请|想要|需要|来)(搭配|推荐|搭建|组合|准备穿搭)|(^|[，,。！!？? ])(搭配|推荐|搭建|组合).{0,12}(衣服|穿搭|搭配|一套|三套|几套)|用.{0,20}(搭配|组合).{0,10}套|搭配一下|再来.{0,6}套|再搭.{0,10}套|怎么穿|穿什么|重新(搭配|推荐|来)|换(一批|几套|一套)|再推荐|再搭|再来几套|都不喜欢|还有别的吗");
            outfit|=followsRecommendation&&direct.strip().length()<=60&&has(direct,"重新|换|再|别的|不喜欢|不要");
            if(outfit) {tools.addAll(Set.of("recommend_outfits","present_outfits","get_outfit_candidates","build_outfits","propose_outfits"));purposes.add("OUTFIT");}
        }
        if(!negative&&!ability) {
            boolean edit=has(direct,"(调整|修改|替换|加入|移除|去掉|换掉|整理).{0,15}(画板|贴纸|搭配|外套|上衣|裤子|裙子|组合)|把.{0,18}(移除|去掉|换成|放大|缩小)");
            if(edit){tools.addAll(Set.of("edit_workspace","propose_edit"));purposes.add("OUTFIT");}
            if(has(direct,"(打开|带我去|跳转|前往|进入).{0,30}"))tools.add("navigate");
            if(has(direct,"(保存|存下).{0,20}(搭配|组合|画板|这套)|保存一下|保存当前"))tools.add("request_save");
            if(has(direct,"(记住|记下来|长期偏好|一直喜欢|以后都|总是喜欢)"))tools.addAll(Set.of("remember_preference","edit_memory_document"));
            if(has(direct,"(帮我|请|把|将|我要|我想|改成|修改|收藏|取消收藏|回收|恢复).{0,40}(改|名称|名字|收藏|季节|品类|回收|恢复)|取消收藏|收藏这件"))tools.add("prepare_command");
            var platformKinds=new HashSet<String>();
            if(has(direct,"(批量|全部|所有|这几件).{0,30}(修改|改成|改为|收藏|回收|恢复)|把.{0,20}(全部|所有|这几件).{0,20}(修改|改成|收藏|回收|恢复)"))platformKinds.add("GARMENT_BATCH");
            if(has(direct,"(识别|重新识别|增强|处理图片).{0,15}(这件|衣物|衣服|图片)|把.{0,15}(重新识别|增强)|识别一下|增强一下")){platformKinds.add("GARMENT_WORKFLOW");tools.add("get_processing_options");}
            if(has(direct,"(取消|停止).{0,20}(任务|识别|生成|处理)"))platformKinds.add("TASK_CANCEL");
            if(has(direct,"(修改|更新|设置|改为|改成).{0,15}(个人资料|身高|体重|身材|性别|偏好)|(身高|体重|身材|性别|偏好).{0,15}(改为|改成|修改|更新)")){platformKinds.add("PROFILE_EDIT");tools.add("get_profile_for_edit");}
            if(has(direct,"(删除|清除).{0,15}(基准|人物|人偶)"))platformKinds.add("BASELINE_DELETE");
            if(has(direct,"(删除|清除).{0,15}(OOTD|穿搭图|试穿图)"))platformKinds.add("OOTD_DELETE");
            if(!platformKinds.isEmpty()){tools.add("prepare_platform_operation");platformKinds.forEach(k->purposes.add("COMMAND:"+k));}
            if(has(direct,"(生成|制作).{0,15}(基准|人物|人偶)")){tools.addAll(Set.of("get_render_options","prepare_generation"));purposes.add("BASELINE");}
            if(has(direct,"(生成|制作).{0,15}(OOTD|穿搭图|试穿图)")){tools.addAll(Set.of("get_render_options","prepare_generation"));purposes.add("OOTD");}
        }
        if(!ability&&has(direct,"(忘记|忘掉|删除|清空).{0,20}(记忆|偏好|身高|体重|身材|围度)|不要再记.{0,20}"))tools.add("edit_memory_document");
        return new Turn(Set.copyOf(tools),Set.copyOf(purposes));
    }
    public static void require(Turn turn,String tool) {
        if(!turn.tools().contains(tool))throw new ProcessingException(HttpStatus.FORBIDDEN,"AGENT_CAPABILITY_DENIED","当前消息没有授权此操作，请明确说明要执行的动作。查询不会触发搭配或修改。");
    }
    private AgentIntentPolicy() {}
}
