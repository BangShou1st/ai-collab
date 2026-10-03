package com.shitulelv.aicollab.document.infrastructure;
import com.shitulelv.aicollab.document.domain.service.DocumentChunker;
import com.shitulelv.aicollab.document.infrastructure.parser.*;
import org.junit.jupiter.api.Test;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import java.io.ByteArrayOutputStream;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class DocumentSourceOffsetTest {
    @Test void headingsAndWhitespaceDoNotShiftCleanTextOffsets() {
        String text="# 第一章\n\n  第一页正文。  \n\n# 第二章\n\n第二页正文。\n";
        int second=text.indexOf("# 第二章");
        var chunks=new DocumentChunker().split(text,List.of(new ParsedDocument.PageBoundary(1,0),new ParsedDocument.PageBoundary(2,second)));
        assertThat(chunks).hasSize(2);
        for(var chunk:chunks) {
            int from=((Number)chunk.metadata().get("charFrom")).intValue(); int through=((Number)chunk.metadata().get("charThrough")).intValue();
            assertThat(text.substring(from,through)).isEqualTo(chunk.content());
        }
        assertThat(chunks.get(1).metadata()).containsEntry("pageNumber",2);
    }
    @Test void pdfPageBoundariesUseTheCleanedCoordinateIncludingSaxWhitespace() throws Exception {
        byte[] bytes;
        try(var pdf=new PDDocument(); var out=new ByteArrayOutputStream()) {
            for(int n=1;n<=3;n++) {
                var page=new PDPage(); pdf.addPage(page);
                try(var stream=new PDPageContentStream(pdf,page)) {
                    stream.beginText();stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),12);stream.newLineAtOffset(30,700);stream.showText("Page    "+n+"    content.");stream.endText();
                }
            }
            pdf.save(out);bytes=out.toByteArray();
        }
        var parser=new TikaDocumentParser();
        try {
            var parsed=parser.parse(bytes,"sample.pdf","application/pdf");
            assertThat(parsed.pageBoundaries()).hasSize(3);
            for(var b:parsed.pageBoundaries()) assertThat(parsed.text().substring(b.charOffset())).startsWith("Page "+b.pageNumber()+" content.");
            var chunks=new DocumentChunker().split(parsed.text(),parsed.pageBoundaries());
            assertThat(chunks.getFirst().metadata()).containsEntry("pageThrough",3);
        } finally {parser.shutdownParserExecutor();}
    }
}
