package com.jovx.api.catalog.model;

import static org.junit.jupiter.api.Assertions.*;

import com.jovx.api.catalog.model.CatalogModels.*;
import com.jovx.api.catalog.storage.ImageProcessor;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class CatalogRulesTest {
  @Test
  void minimumPointsAreTwoPercentRoundedHalfUp() {
    assertEquals(200, Rules.minimumPoints(10_000));
    assertEquals(247, Rules.minimumPoints(12_345)); // 246.9
    assertEquals(1, Rules.minimumPoints(25)); // 0.5 rounds up
    assertEquals(0, Rules.minimumPoints(24)); // 0.48
  }

  @Test
  void memberPriceFollowsThePartnerMinimumDiscount() {
    var ten = new BigDecimal("10.00");
    assertEquals(9_000, Rules.automaticMemberPrice(10_000, ten));
    assertEquals(8_999, Rules.automaticMemberPrice(9_999, ten)); // 8,999.1
    assertEquals(7_866, Rules.automaticMemberPrice(9_999, new BigDecimal("21.33"))); // 7,866.21
    assertTrue(Rules.validCustomMemberPrice(8_000, 10_000, ten));
    assertTrue(Rules.validCustomMemberPrice(9_000, 10_000, ten));
    assertFalse(Rules.validCustomMemberPrice(9_500, 10_000, ten));
    assertFalse(Rules.validCustomMemberPrice(11_000, 10_000, ten));
  }

  @Test
  void onlyDataChangesNeedReviewAndOnlyApprovedPublishedProductsAreShown() {
    var p = new Product(null, null, null, null, java.util.UUID.randomUUID(), "برغر", null, 10_000, "SYP", null, 200,
        true, 5, ReviewStatus.APPROVED, null, true, 1, null, null);
    var same = new ProductDraft(p.categoryId(), "برغر", null, 10_000, null, 200, true, 0);
    assertFalse(Rules.needsReview(p, same), "stock alone");
    assertTrue(Rules.needsReview(p, new ProductDraft(p.categoryId(), "برغر", null, 11_000, null, 220, true, 5)));
    assertTrue(Rules.visibleToMembers(p, true, true));
    assertFalse(Rules.visibleToMembers(p, false, true));
    assertFalse(Rules.visibleToMembers(new Product(null, null, null, null, p.categoryId(), "برغر", null, 10_000, "SYP",
        null, 200, false, null, ReviewStatus.APPROVED, null, false, 1, null, null), true, true));
  }

  @Test
  void imagesAreDetectedByContentAndShrunk() throws Exception {
    var processor = new ImageProcessor();
    var out = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(3000, 1500, BufferedImage.TYPE_INT_ARGB), "png", out);
    var processed = processor.process(out.toByteArray());
    assertEquals("image/jpeg", processed.contentType());
    var image = ImageIO.read(new java.io.ByteArrayInputStream(processed.bytes()));
    assertEquals(1200, image.getWidth());
    assertEquals(600, image.getHeight());
    var notImage = assertThrows(ImageProcessor.Rejected.class, () -> processor.process("GIF89a....".getBytes()));
    assertEquals(ImageProcessor.Failure.UNSUPPORTED, notImage.failure());
    var tooLarge = assertThrows(ImageProcessor.Rejected.class, () -> processor.process(new byte[ImageProcessor.MAX_BYTES + 1]));
    assertEquals(ImageProcessor.Failure.TOO_LARGE, tooLarge.failure());
  }
}
