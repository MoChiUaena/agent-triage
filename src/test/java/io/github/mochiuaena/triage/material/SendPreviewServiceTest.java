package io.github.mochiuaena.triage.material;

import io.github.mochiuaena.triage.settings.*;
import io.github.mochiuaena.triage.domain.TriageModel.ModelSource;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.web.server.ResponseStatusException;
import static io.github.mochiuaena.triage.material.MaterialModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SendPreviewServiceTest {
    final MaterialService materials=mock(MaterialService.class);
    final ProviderRegistry providers=mock(ProviderRegistry.class);
    final UUID provider=UUID.randomUUID(), repository=UUID.randomUUID();
    final Selection selection=new Selection(Type.PR,UUID.randomUUID(),null,repository,null);
    final Instant now=Instant.parse("2026-10-15T00:00:00Z");
    final MutableClock clock=new MutableClock(now);
    SendPreviewService service;
    Material material(String version) {
        return new Material(new Reference(Type.PR,selection.reportId(),new Scope(null,repository,null),version,"a".repeat(64),now,"UNCONFIRMED","/development.html"),"saved text",List.of("summary"),List.of("source omitted"));
    }
    @BeforeEach void setup() {
        when(materials.resolve(selection)).thenReturn(material("1"));
        when(providers.selection()).thenReturn(new ProviderConfig.Selection("MODEL",provider,"LOCAL"));
        model(1);
        service=new SendPreviewService(materials,providers,clock,Duration.ofMinutes(10),2);
    }
    void model(long version) { when(providers.activeModel()).thenReturn(new ProviderRegistry.ModelAccess(null,"fixture-model",new ModelSource(provider,"fixture",version),Duration.ofSeconds(1),"https://model.example/v1")); }
    @Test void consumesExactlyPreviewedTextAndOnlyOnceAfterConfirmation() {
        Preview preview=service.create(List.of(selection));
        assertThatThrownBy(() -> service.consume(preview.id(),false)).isInstanceOf(ResponseStatusException.class);
        Consumed sent=service.consume(preview.id(),true);
        assertThat(sent.text()).isEqualTo(preview.text());
        assertThat(sent.contentSha256()).isEqualTo(preview.contentSha256());
        assertThat(sent.references()).allMatch(r -> r.confirmation().equals("CONFIRMED"));
        assertThatThrownBy(() -> service.consume(preview.id(),true)).isInstanceOf(ResponseStatusException.class);
    }
    @Test void expiryRejectsBeforeResolvingOrSending() {
        Preview preview=service.create(List.of(selection)); clock.value=now.plusSeconds(600);
        assertThatThrownBy(() -> service.consume(preview.id(),true)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("过期");
    }
    @Test void changedProviderConfigurationOrSelectionInvalidatesPreview() {
        Preview preview=service.create(List.of(selection)); model(2);
        assertThatThrownBy(() -> service.consume(preview.id(),true)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("模型");
        model(1); UUID id=service.create(List.of(selection)).id();
        when(providers.selection()).thenReturn(new ProviderConfig.Selection("MODEL",provider,"ENV"));
        assertThatThrownBy(() -> service.consume(id,true)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("模型");
    }
    @Test void changedOrDeletedMaterialIsRejectedAndNeverSubstituted() {
        Preview preview=service.create(List.of(selection));
        when(materials.resolve(selection)).thenReturn(material("2"));
        assertThatThrownBy(() -> service.consume(preview.id(),true)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("材料");
        when(materials.resolve(selection)).thenReturn(material("1")); Preview removed=service.create(List.of(selection));
        when(materials.resolve(selection)).thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.consume(removed.id(),true)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("材料");
    }
    @Test void admissionIsBoundedAndExpiredEntriesReleaseCapacity() {
        service.create(List.of(selection)); service.create(List.of(selection));
        assertThatThrownBy(() -> service.create(List.of(selection))).isInstanceOf(ResponseStatusException.class);
        clock.value=now.plusSeconds(601); assertThat(service.create(List.of(selection))).isNotNull();
    }
    @Test void mixedScopesAndDuplicateReferencesCannotEnterPreview() {
        Selection other=new Selection(Type.PR,UUID.randomUUID(),null,UUID.randomUUID(),null);
        when(materials.resolve(other)).thenReturn(new Material(new Reference(Type.PR,other.reportId(),new Scope(null,other.repositoryId(),null),"1","b",now,"UNCONFIRMED","/"),"other",List.of(),List.of()));
        assertThatThrownBy(() -> service.create(List.of(selection,other))).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.create(List.of(selection,selection))).isInstanceOf(ResponseStatusException.class);
    }
    static class MutableClock extends Clock {
        Instant value; MutableClock(Instant value) { this.value=value; }
        public ZoneId getZone() { return ZoneOffset.UTC; } public Clock withZone(ZoneId zone) { return this; } public Instant instant() { return value; }
    }
}
