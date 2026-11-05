package com.example.lancerpick.contract;

import static org.assertj.core.api.Assertions.*;

import com.example.lancerpick.contract.document.ContractPdfRenderer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ContractPdfRendererTests {
  private final JsonMapper mapper = JsonMapper.builder().build();
  private final ContractPdfRenderer renderer = new ContractPdfRenderer(mapper);

  @Test
  void embedsKoreanAndWrapsLongTermsAcrossPagesWithoutExternalContent() throws Exception {
    String standard;
    try (var input = getClass().getResourceAsStream("/contracts/standard-v1.txt")) {
      standard = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }
    String terms = "특약: 작업 결과물은 구매자에게 전달합니다. ".repeat(130);
    byte[] pdf =
        renderer.render(
            123,
            2,
            mapper.writeValueAsString(
                Map.of(
                    "productTitle",
                    "랜서픽 한글 서비스",
                    "total",
                    100001,
                    "deposit",
                    50000,
                    "balance",
                    50001,
                    "standardBody",
                    standard,
                    "specialTerms",
                    terms,
                    "requirements",
                    "<script>https://example.invalid/private</script>")));
    try (var document = Loader.loadPDF(pdf)) {
      String text = new PDFTextStripper().getText(document);
      assertThat(document.getNumberOfPages()).isGreaterThan(1);
      assertThat(text)
          .contains("랜서픽 전자계약서", "한글 서비스", "100001", "50000", "50001", "특약", "<script>");
      for (var page : document.getPages()) {
        assertThat(page.getAnnotations()).isEmpty();
        for (var name : page.getResources().getFontNames()) {
          assertThat(page.getResources().getFont(name).isEmbedded()).isTrue();
        }
      }
      assertThat(document.getDocumentCatalog().getOpenAction()).isNull();
      // Synthetic contract, kept under ignored build output for visual review.
      Path output = Path.of("build/reports/contracts");
      Files.createDirectories(output);
      Files.write(output.resolve("korean-contract.pdf"), pdf);
      var rasterizer = new PDFRenderer(document);
      for (int i = 0; i < document.getNumberOfPages(); i++) {
        ImageIO.write(
            rasterizer.renderImageWithDPI(i, 96),
            "png",
            output.resolve("page-" + (i + 1) + ".png").toFile());
      }
    }
  }

  @Test
  void unsupportedCharactersAreExplicitAndTabsDoNotBreakRendering() throws Exception {
    byte[] pdf =
        renderer.render(1, 1, mapper.writeValueAsString(Map.of("specialTerms", "한글\t특약 😀\u0001")));
    try (var document = Loader.loadPDF(pdf)) {
      assertThat(new PDFTextStripper().getText(document))
          .contains("한글", "특약", "[U+1F600]", "[U+1]");
    }
  }

  @Test
  void oversizedSnapshotIsRejectedBeforeRendering() {
    assertThatThrownBy(() -> renderer.render(1, 1, "a".repeat(250001)))
        .isInstanceOf(java.io.IOException.class);
  }
}
