package com.prayerroster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.prayerroster.domain.AllowedEmail;
import com.prayerroster.repository.AllowedEmailRepository;
import com.prayerroster.service.dto.AllowedEmailDTO;
import com.prayerroster.web.rest.errors.BadRequestAlertException;
import com.prayerroster.web.rest.errors.EntityNotFoundException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AllowedEmailServiceTest {

    @Mock
    private AllowedEmailRepository allowedEmailRepository;

    private AllowedEmailService service;

    @BeforeEach
    void setUp() {
        service = new AllowedEmailService(allowedEmailRepository);
    }

    @Test
    void invite_storesTheEmailLowercasedAndTrimmed() {
        when(allowedEmailRepository.existsByEmailIgnoringCase("jean@example.com")).thenReturn(false);
        when(allowedEmailRepository.save(any(AllowedEmail.class))).thenAnswer(inv -> inv.getArgument(0));

        service.invite("  Jean@Example.COM  ");

        ArgumentCaptor<AllowedEmail> captor = ArgumentCaptor.forClass(AllowedEmail.class);
        verify(allowedEmailRepository).save(captor.capture());
        assertThat(captor.getValue().getEmail()).isEqualTo("jean@example.com");
    }

    @Test
    void invite_rejectsADuplicate() {
        when(allowedEmailRepository.existsByEmailIgnoringCase("jean@example.com")).thenReturn(true);

        assertThatThrownBy(() -> service.invite("jean@example.com")).isInstanceOf(BadRequestAlertException.class);

        verify(allowedEmailRepository, never()).save(any());
    }

    @Test
    void findAll_mapsToDtos() {
        AllowedEmail entity = new AllowedEmail();
        entity.setId(1L);
        entity.setEmail("jean@example.com");
        when(allowedEmailRepository.findAll()).thenReturn(List.of(entity));

        List<AllowedEmailDTO> result = service.findAll();

        assertThat(result).singleElement().extracting(AllowedEmailDTO::email).isEqualTo("jean@example.com");
    }

    @Test
    void delete_removesAnExistingEntry() {
        when(allowedEmailRepository.existsById(1L)).thenReturn(true);

        service.delete(1L);

        verify(allowedEmailRepository).deleteById(1L);
    }

    @Test
    void delete_rejectsAnUnknownId() {
        when(allowedEmailRepository.existsById(9L)).thenReturn(false);

        assertThatThrownBy(() -> service.delete(9L)).isInstanceOf(EntityNotFoundException.class);

        verify(allowedEmailRepository, never()).deleteById(any());
    }
}
