package com.smartcloset.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

@Service
public class HelpService {
    public record Article(String id,String title,String markdown,String href) {}
    public record Hit(String id,String title,String excerpt,String href) {}
    private final List<Article> articles;
    public HelpService() throws IOException {
        var list=new ArrayList<Article>();
        for(var resource:new PathMatchingResourcePatternResolver().getResources("classpath:help/*.md")) {
            String id=Objects.requireNonNull(resource.getFilename()).replace(".md","");
            String text=new String(resource.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
            String title=text.lines().filter(l->l.startsWith("# ")).findFirst().orElse("# 帮助").substring(2);
            list.add(new Article(id,title,text,"/help?article="+id));
        }
        articles=list.stream().sorted(Comparator.comparing(Article::id)).toList();
    }
    public List<Article> articles(){return articles;}
    public List<Hit> search(String query) {
        var text=AgentRepository.clean(query,300).toLowerCase(Locale.ROOT).replaceAll("怎么|如何|请问|平台|一下|可以|是否|什么|我想|帮我|使用", " ");
        var terms=new HashSet<String>();
        for(var word:text.split("[^\\p{L}\\p{N}]+")) {
            if(word.isBlank())continue;
            terms.add(word);
            if(word.matches(".*[\\p{IsHan}].*"))for(int i=0;i<word.length()-1;i++)terms.add(word.substring(i,i+2));
        }
        return articles.stream().map(a->{
            String body=a.markdown().toLowerCase(Locale.ROOT);
            int score=terms.stream().mapToInt(t->body.contains(t)?(a.title().contains(t)?4:1):0).sum();
            return Map.entry(a,score);
        }).filter(e->e.getValue()>=2).sorted((a,b)->Integer.compare(b.getValue(),a.getValue())).limit(3)
          .map(e->new Hit(e.getKey().id(),e.getKey().title(),e.getKey().markdown(),e.getKey().href())).toList();
    }
}
