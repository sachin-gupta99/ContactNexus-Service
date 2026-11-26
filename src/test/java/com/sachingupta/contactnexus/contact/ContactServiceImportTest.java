package com.sachingupta.contactnexus.contact;

import com.sachingupta.contactnexus.contact.dto.ContactImportResult;
import com.sachingupta.contactnexus.contact.dto.ContactRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContactServiceImportTest {

    @Mock
    ContactRepository repository;

    @InjectMocks
    ContactService service;

    private static ContactRequest contact(String firstName, String email) {
        return new ContactRequest(firstName, null, email, null, null, null, null,
                null, null, null, null, null, null, null);
    }

    @SuppressWarnings("unchecked")
    private List<Contact> saved() {
        ArgumentCaptor<List<Contact>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(captor.capture());
        return captor.getValue();
    }

    @Test
    void skipsEmailsTheOwnerAlreadyHasIgnoringCase() {
        when(repository.findEmailsLowercase(7L)).thenReturn(List.of("ann@x.com"));

        ContactImportResult result = service.importAll(7L, List.of(
                contact("Ann", "ANN@x.com"),
                contact("Bob", "bob@x.com")));

        assertThat(result).isEqualTo(new ContactImportResult(1, 1));
        assertThat(saved()).extracting(Contact::getFirstName).containsExactly("Bob");
    }

    @Test
    void keepsOnlyTheFirstOfRepeatsWithinTheBatch() {
        when(repository.findEmailsLowercase(7L)).thenReturn(List.of());

        ContactImportResult result = service.importAll(7L, List.of(
                contact("Cat", "cat@x.com"),
                contact("Cat again", " Cat@X.com "),
                contact("Dan", "dan@x.com")));

        assertThat(result).isEqualTo(new ContactImportResult(2, 1));
        assertThat(saved()).extracting(Contact::getFirstName).containsExactly("Cat", "Dan");
    }

    @Test
    void savedContactsBelongToTheCaller() {
        when(repository.findEmailsLowercase(7L)).thenReturn(List.of());
        when(repository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        service.importAll(7L, List.of(contact("Eve", "eve@x.com")));

        assertThat(saved()).allSatisfy(c -> assertThat(c.getOwnerId()).isEqualTo(7L));
    }
}
