package com.overlord.audio.openal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.overlord.audio.MusicHandle;
import java.lang.reflect.Modifier;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class MusicHandleDomainAccessTest {
    @Test
    void plannedBackendSubpackageCanIssueAndValidateOwnerIsolatedHandles() {
        MusicHandle.Domain firstDomain = MusicHandle.newDomain();
        MusicHandle.Domain secondDomain = MusicHandle.newDomain();
        MusicHandle first = firstDomain.issue(1L);
        MusicHandle second = secondDomain.issue(1L);
        MusicHandle forged = new MusicHandle(first.value());

        assertEquals(first.value(), second.value());
        assertSame(first, firstDomain.requireOwned(first));
        assertSame(second, secondDomain.requireOwned(second));
        assertThrows(IllegalArgumentException.class, () -> firstDomain.requireOwned(second));
        assertThrows(IllegalArgumentException.class, () -> secondDomain.requireOwned(first));
        assertThrows(IllegalArgumentException.class, () -> firstDomain.requireOwned(forged));
    }

    @Test
    void publicDomainCapabilityDoesNotExposeItsOwnerToken() {
        Set<String> publicDeclaredMethods =
                Stream.of(MusicHandle.Domain.class.getDeclaredMethods())
                        .filter(method -> Modifier.isPublic(method.getModifiers()))
                        .map(method -> method.getName())
                        .collect(Collectors.toSet());

        assertEquals(Set.of("issue", "requireOwned"), publicDeclaredMethods);
    }
}
