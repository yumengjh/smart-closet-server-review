package com.smartcloset.person;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class WearerProfilesTest {
 private WearerProfiles.Profile profile(String sex,Double height,Double weight){return new WearerProfiles.Profile(7,sex,height,weight,null,Map.of(),Map.of(),null);}
 @Test void defaultsAreFrozenWithoutMutatingRealData(){var p=profile("MALE",null,null);var s=WearerProfiles.snapshot(p);assertEquals(170,s.heightCm());assertEquals(63.6,s.weightKg());assertNull(p.heightCm());assertNull(p.weightKg());assertEquals("DERIVED_DEFAULT",s.sources().get("weightKg"));assertEquals(56.3,WearerProfiles.snapshot(profile("FEMALE",null,null)).weightKg());assertEquals(75.3,WearerProfiles.snapshot(profile("MALE",185d,null)).weightKg());}
 @Test void realWeightIsNeverReplacedOrUsedToInferHeight(){var s=WearerProfiles.snapshot(profile("FEMALE",null,80d));assertEquals(160,s.heightCm());assertEquals(80,s.weightKg());assertEquals("USER_REPORTED",s.sources().get("weightKg"));assertTrue(s.measurements().isEmpty());}
 @Test void missingSexAndInvalidNumbersAreNotSilentlyDefaulted(){assertThrows(com.smartcloset.image.ProcessingException.class,()->WearerProfiles.snapshot(profile(null,null,null)));for(double h:new double[]{0,-1,Double.NaN,Double.POSITIVE_INFINITY})assertThrows(com.smartcloset.image.ProcessingException.class,()->WearerProfiles.snapshot(profile("MALE",h,null)));}
}
