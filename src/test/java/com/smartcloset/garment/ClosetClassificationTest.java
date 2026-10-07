package com.smartcloset.garment;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ClosetClassificationTest {
 @Test void skirtFamilySeparatesDressAndSkirtWithoutGuessingAmbiguousSets(){assertEquals("DRESS",ClosetClassification.family(List.of("连衣裙")));assertEquals("SKIRT",ClosetClassification.family(List.of("半身裙")));assertTrue(ClosetClassification.matches(List.of("连衣裙"),"裙子"));assertTrue(ClosetClassification.matches(List.of("半身裙"),"裙子"));for(String ambiguous:List.of("裙装","套装","两件套","西装套装"))assertEquals("UNKNOWN",ClosetClassification.family(List.of(ambiguous)));assertEquals("UNKNOWN",ClosetClassification.family(List.of("衬衫","长裤")));}
}
