package com.smartcloset.storage;

import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Examples published in the AWS S3 SigV4 documentation ("Examples: Signature Calculations"). */
class SigV4Test {
    private final SigV4 aws = new SigV4("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", "us-east-1");

    @Test void getObjectWithRangeMatchesTheAwsExample() {
        var headers = new TreeMap<String, String>();
        headers.put("host", "examplebucket.s3.amazonaws.com");
        headers.put("range", "bytes=0-9");
        headers.put("x-amz-content-sha256", SigV4.EMPTY_SHA256);
        headers.put("x-amz-date", "20130524T000000Z");
        assertEquals("AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request, "
                + "SignedHeaders=host;range;x-amz-content-sha256;x-amz-date, Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41",
                aws.authorization("GET", "/test.txt", Map.of(), headers));
    }

    @Test void listObjectsMatchesTheAwsExample() {
        var headers = new TreeMap<String, String>();
        headers.put("host", "examplebucket.s3.amazonaws.com");
        headers.put("x-amz-content-sha256", SigV4.EMPTY_SHA256);
        headers.put("x-amz-date", "20130524T000000Z");
        assertTrue(aws.authorization("GET", "/", Map.of("max-keys", "2", "prefix", "J"), headers)
                .endsWith("Signature=34b48302e7b5fa45bde8084f4b7868a86f0a534bc59db6670ed5711ef69dc6f7"));
    }

    @Test void presignedGetMatchesTheAwsExample() {
        String query = aws.presignedQuery("GET", "/test.txt", "examplebucket.s3.amazonaws.com", Map.of(), Instant.parse("2013-05-24T00:00:00Z"), 86400);
        assertEquals("X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=AKIAIOSFODNN7EXAMPLE%2F20130524%2Fus-east-1%2Fs3%2Faws4_request"
                + "&X-Amz-Date=20130524T000000Z&X-Amz-Expires=86400&X-Amz-SignedHeaders=host"
                + "&X-Amz-Signature=aeeed9bbccd4d02ee5c0109b86d86835f995330da4c265957d157751f604d404", query);
    }

    @Test void encodingKeepsSlashesOnlyInPaths() {
        assertEquals("closet/workflows/a%20b/%E8%A3%A4.png", SigV4.encode("closet/workflows/a b/裤.png", false));
        assertEquals("a%2Fb%2Bc~", SigV4.encode("a/b+c~", true));
    }
}
