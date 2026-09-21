package com.housedevinci.einvoice.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Pass 2 on D5-02: the shapes the pass-1 fix did not have fixtures for. */
class CipherProbePr7bWalkTest {

  record InMapValue(Map<String, Leaf> byKey) {}

  record Leaf(String deep) {}

  record NestedGeneric(Optional<List<Leaf>> maybeMany) {}

  record WithEnum(Colour colour, String plain) {}

  enum Colour {
    RED
  }

  sealed interface Shape permits Circle {}

  record Circle(String label) implements Shape {}

  record WithSealed(Shape shape) {}

  @SuppressWarnings("unchecked")
  private static List<String> walk(Class<?> type, String prefix) throws Exception {
    Method m =
        CipherProbePreflightTest.class.getDeclaredMethod(
            "stringPaths", Class.class, String.class, List.class);
    m.setAccessible(true);
    try {
      return (List<String>) m.invoke(null, type, prefix, new ArrayList<Class<?>>());
    } catch (java.lang.reflect.InvocationTargetException e) {
      if (e.getCause() instanceof Error err) {
        throw err;
      }
      throw e;
    }
  }

  @Test
  void probe_a_record_inside_a_map_value_is_reached() throws Exception {
    List<String> paths = walk(InMapValue.class, "InMapValue");
    System.out.println("D7-02 map value: " + paths);
    assertThat(paths).contains("InMapValue.byKey{}.deep");
  }

  @Test
  void probe_a_nested_generic_fails_the_build_rather_than_being_skipped() {
    assertThatThrownBy(() -> walk(NestedGeneric.class, "NestedGeneric"))
        .as("Optional<List<Leaf>> is neither walked nor silently skipped")
        .isInstanceOf(AssertionError.class);
  }

  @Test
  void probe_a_sealed_interface_fails_the_build_rather_than_being_skipped() {
    assertThatThrownBy(() -> walk(WithSealed.class, "WithSealed"))
        .isInstanceOf(AssertionError.class);
  }

  @Test
  void probe_an_enum_component_is_not_a_silent_string_carrier() throws Exception {
    List<String> paths = walk(WithEnum.class, "WithEnum");
    System.out.println("D7-02 enum: " + paths);
    assertThat(paths).containsExactly("WithEnum.plain");
  }
}
