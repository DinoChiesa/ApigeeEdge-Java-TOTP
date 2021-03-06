package com.google.apigee.edgecallouts;

import com.apigee.flow.execution.ExecutionContext;
import com.apigee.flow.execution.ExecutionResult;
import com.apigee.flow.execution.spi.Execution;
import com.apigee.flow.message.MessageContext;
import com.eatthepath.otp.TimeBasedOneTimePasswordGenerator;
import com.google.apigee.encoding.Base16;
import com.google.apigee.encoding.Base32;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import javax.crypto.spec.SecretKeySpec;

public class TotpCallout extends CalloutBase implements Execution {
  private static final int DEFAULT_TIME_STEP_SECONDS = 30;
  private static final int DEFAULT_CODE_DIGITS = 6;
  private final String DEFAULT_HASH_FUNCTION = "HmacSHA1";

  public TotpCallout(Map properties) {
    super(properties);
  }

  private int getTimeStep(MessageContext msgCtxt) throws Exception {
    return getIntegerWithDefault(msgCtxt, "time-step", DEFAULT_TIME_STEP_SECONDS);
  }

  private int getCodeDigits(MessageContext msgCtxt) throws Exception {
    return getIntegerWithDefault(msgCtxt, "code-digits", DEFAULT_CODE_DIGITS);
  }

  private int getIntegerWithDefault(MessageContext msgCtxt, String name, int defaultValue)
      throws Exception {
    String v = getSimpleOptionalProperty(name, msgCtxt);
    if (v == null) {
      return defaultValue;
    }
    try {
      return Integer.parseInt(v);
    } catch (Exception ignoredException) {
      return defaultValue;
    }
  }

  private byte[] decodeKey(String initialKey) {
    String value = (String) this.properties.get("decode-key");
    if ("hex".equals(value) || "base16".equals(value)) {
      return Base16.decode(initialKey);
    }
    if ("base32".equals(value)) {
      return Base32.decode(initialKey);
    }
    if ("base64url".equals(value)) {
      return Base64.getUrlDecoder().decode(initialKey);
    }
    if ("base64".equals(value)) {
      return Base64.getDecoder().decode(initialKey);
    }
    // utf-8 encoded
    return initialKey.getBytes(StandardCharsets.UTF_8);
  }

  private byte[] getDecodedKey(MessageContext msgCtxt) throws Exception {
    String keyString = getSimpleRequiredProperty("key", msgCtxt);
    return decodeKey(keyString);
  }

  private Instant getTime(MessageContext msgCtxt) {
    String value = (String) this.properties.get("fake-time-millis");
    if (value != null) {
      try {
        value = value.trim();
        value = resolvePropertyValue(value, msgCtxt);
        return Instant.ofEpochMilli(Long.parseLong(value));
      } catch (Exception ignoredException) {
        return java.time.Instant.now();
      }
    }
    value = (String) this.properties.get("fake-time-seconds");
    if (value != null) {
      try {
        value = value.trim();
        value = resolvePropertyValue(value, msgCtxt);
        return Instant.ofEpochSecond(Long.parseLong(value));
      } catch (Exception ignoredException) {
        return java.time.Instant.now();
      }
    }
    return java.time.Instant.now();
  }

  private boolean getWantLeadingZeros() {
    String value = (String) this.properties.get("leading-zeros");
    if (value == null) return false;
    if (value.trim().toLowerCase().equals("true")) return true;
    return false;
  }

  private String getHashFunction(MessageContext msgCtxt) throws Exception {
    String value = getSimpleOptionalProperty("hash-function", msgCtxt);
    if (value == null) return DEFAULT_HASH_FUNCTION;
    value = value.toLowerCase();
    if ("sha1".equals(value) || "hmacsha1".equals(value)) return "HmacSHA1";
    if ("sha256".equals(value) || "hmacsha256".equals(value)) return "HmacSHA256";
    if ("sha512".equals(value) || "hmacsha512".equals(value)) return "HmacSHA512";
    return DEFAULT_HASH_FUNCTION;
  }

  public ExecutionResult execute(final MessageContext msgCtxt, final ExecutionContext execContext) {
    try {
      final int timeStepSizeInSeconds = getTimeStep(msgCtxt);
      final int codeDigits = getCodeDigits(msgCtxt);
      final Key key = new SecretKeySpec(getDecodedKey(msgCtxt), "RAW");
      final String hashAlgorithm = getHashFunction(msgCtxt);
      msgCtxt.setVariable(varName("hashfunction"), hashAlgorithm);

      final TimeBasedOneTimePasswordGenerator totp =
          new TimeBasedOneTimePasswordGenerator(
              Duration.ofSeconds(timeStepSizeInSeconds), codeDigits, hashAlgorithm);

      final Instant timestamp = getTime(msgCtxt);

      String code = "";
      if (getWantLeadingZeros()) {
        String format = String.format("%%0%dd", codeDigits);
        code = String.format(format, totp.generateOneTimePassword(key, timestamp));
      } else {
        code = Integer.toString(totp.generateOneTimePassword(key, timestamp));
      }

      msgCtxt.setVariable(varName("time"), Long.toString(timestamp.getEpochSecond()));
      msgCtxt.setVariable(varName("code"), code);

      final String expectedValue = getSimpleOptionalProperty("expected-value", msgCtxt);
      if (expectedValue != null) {
        if (!expectedValue.equals(code)) {
          msgCtxt.setVariable(varName("error"), "TOTP mismatch");
          msgCtxt.setVariable("fault.name", "totp_mismatch");
          return ExecutionResult.ABORT;
        }
      }

      return ExecutionResult.SUCCESS;
    } catch (Exception e) {
      setExceptionVariables(e, msgCtxt);
      return ExecutionResult.ABORT;
    }
  }
}
