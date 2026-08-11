package dev.xirreal.viewfinder.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ShaderLogParserTest {
   @Test
   void parsesCommonDriverFormats() {
      var messages = ShaderLogParser.parse("ERROR: 0:42: undeclared identifier\ncomposite.fsh(19) : warning unused value");
      assertEquals(2, messages.size());
      assertEquals(42, messages.get(0).line());
      assertEquals("composite.fsh", messages.get(1).file());
      assertEquals("warning", messages.get(1).severity());
   }
}
