package net.tfminecraft.musicalinstruments.keyboard;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.RegistryBuilderFactory;
import io.papermc.paper.registry.data.InlinedRegistryBuilderProvider;
import io.papermc.paper.registry.data.dialog.DialogRegistryEntry;
import io.papermc.paper.registry.data.dialog.DialogInstancesProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.mockito.MockedStatic;

/**
 * MockBukkit has no Paper dialog implementation, so tests stand in for the two providers the
 * dialog API delegates to. Builders are deep-stub mocks; each created dialog is a fresh mock.
 */
final class DialogStubs implements AutoCloseable {
    final List<Dialog> created = new ArrayList<>();
    private final MockedStatic<DialogInstancesProvider> instances;
    private final MockedStatic<InlinedRegistryBuilderProvider> inlined;

    @SuppressWarnings({"unchecked", "rawtypes"})
    DialogStubs() {
        DialogInstancesProvider provider = mock(DialogInstancesProvider.class, RETURNS_DEEP_STUBS);
        InlinedRegistryBuilderProvider builders = mock(InlinedRegistryBuilderProvider.class);
        org.mockito.Mockito.when(builders.createDialog(any())).thenAnswer(invocation -> {
            Consumer consumer = invocation.getArgument(0);
            RegistryBuilderFactory factory = mock(RegistryBuilderFactory.class);
            DialogRegistryEntry.Builder builder = mock(DialogRegistryEntry.Builder.class, RETURNS_SELF);
            org.mockito.Mockito.when(factory.empty()).thenReturn(builder);
            consumer.accept(factory);
            Dialog dialog = mock(Dialog.class);
            created.add(dialog);
            return dialog;
        });
        instances = mockStatic(DialogInstancesProvider.class);
        instances.when(DialogInstancesProvider::instance).thenReturn(provider);
        inlined = mockStatic(InlinedRegistryBuilderProvider.class);
        inlined.when(InlinedRegistryBuilderProvider::instance).thenReturn(builders);
    }

    @Override
    public void close() {
        instances.close();
        inlined.close();
    }
}
