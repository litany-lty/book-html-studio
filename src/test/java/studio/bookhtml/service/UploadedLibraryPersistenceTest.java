package studio.bookhtml.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import studio.bookhtml.store.BookStore;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UploadedLibraryPersistenceTest {
    @TempDir Path data;
    @Test void uploadPersistsOriginalAndShelfAcrossFreshServicesWithoutAnyCloudCall()throws Exception{
        var config=TestConfigs.config(data,"","");var json=new ObjectMapper().findAndRegisterModules();
        byte[] bytes;
        try(var pdf=new PDDocument();var output=new ByteArrayOutputStream()){
            pdf.addPage(new PDPage(PDRectangle.A4));pdf.addPage(new PDPage(PDRectangle.LETTER));pdf.save(output);bytes=output.toByteArray();
        }
        String id;var first=new BookStore(config,json);var consent=mock(CloudConsentService.class);
        try {
            var service=new BookService(first,new PdfService(config),config);service.setConsentService(consent);
            var book=service.upload(new MockMultipartFile("file","共享样本.pdf","application/pdf",bytes));id=book.id();
            assertEquals(2,service.listForReading().get(0).totalPages());assertArrayEquals(bytes,Files.readAllBytes(first.pdf(id)));
            assertEquals("PENDING",service.page(id,2).status());
        } finally {first.close();}
        var reopened=spy(new BookStore(config,json));
        try {
            var reader=new BookService(reopened,new PdfService(config),config);
            assertEquals(id,reader.listForReading().get(0).id());
            verify(reopened,never()).readPage(anyString(),anyInt());
            assertEquals(2,reader.page(id,2).pageNumber());assertArrayEquals(bytes,Files.readAllBytes(reopened.pdf(id)));
        } finally {reopened.close();}
    }
}
