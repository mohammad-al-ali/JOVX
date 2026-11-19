package com.jovx.api.catalog.storage;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;

/**
 * Decision 64: JPEG, PNG or WebP up to 2 MB; the type is detected from the bytes, never trusted
 * from the request. Output is always a re-encoded JPEG of at most 1200 px, which also strips metadata.
 */
public final class ImageProcessor {
  public static final int MAX_BYTES = 2 * 1024 * 1024;
  static final int MAX_EDGE = 1200;
  static final int MAX_SOURCE_PIXELS = 40_000_000;

  public enum Failure { TOO_LARGE, UNSUPPORTED, UNREADABLE }

  public static final class Rejected extends RuntimeException {
    private final Failure failure;
    Rejected(Failure failure) { super(failure.name()); this.failure = failure; }
    public Failure failure() { return failure; }
  }

  public record Processed(byte[] bytes, String contentType) {}

  public Processed process(byte[] input) {
    if (input == null || input.length == 0 || input.length > MAX_BYTES) throw new Rejected(Failure.TOO_LARGE);
    if (!jpeg(input) && !png(input) && !webp(input)) throw new Rejected(Failure.UNSUPPORTED);
    BufferedImage source = decode(input);
    double scale = Math.min(1.0, (double) MAX_EDGE / Math.max(source.getWidth(), source.getHeight()));
    int w = Math.max(1, (int) Math.round(source.getWidth() * scale)), h = Math.max(1, (int) Math.round(source.getHeight() * scale));
    var target = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = target.createGraphics();
    try {
      g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
      g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
      g.setColor(Color.WHITE); // transparent PNG/WebP areas become white
      g.fillRect(0, 0, w, h);
      g.drawImage(source, 0, 0, w, h, null);
    } finally {
      g.dispose();
    }
    return new Processed(jpegBytes(target), "image/jpeg");
  }

  /** Reads the header dimensions first, so a small file cannot expand into a huge bitmap. */
  private static BufferedImage decode(byte[] input) {
    try (var stream = ImageIO.createImageInputStream(new ByteArrayInputStream(input))) {
      var readers = ImageIO.getImageReaders(stream);
      if (!readers.hasNext()) throw new Rejected(Failure.UNREADABLE);
      var reader = readers.next();
      try {
        reader.setInput(stream, true, true);
        if ((long) reader.getWidth(0) * reader.getHeight(0) > MAX_SOURCE_PIXELS) throw new Rejected(Failure.UNREADABLE);
        return reader.read(0);
      } finally {
        reader.dispose();
      }
    } catch (Rejected e) {
      throw e;
    } catch (IOException | RuntimeException e) {
      throw new Rejected(Failure.UNREADABLE);
    }
  }

  private static byte[] jpegBytes(BufferedImage image) {
    var writer = ImageIO.getImageWritersByFormatName("jpeg").next();
    var out = new ByteArrayOutputStream();
    try (var stream = ImageIO.createImageOutputStream(out)) {
      writer.setOutput(stream);
      var param = writer.getDefaultWriteParam();
      param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
      param.setCompressionQuality(0.85f);
      writer.write(null, new IIOImage(image, null, null), param);
    } catch (IOException e) {
      throw new Rejected(Failure.UNREADABLE);
    } finally {
      writer.dispose();
    }
    return out.toByteArray();
  }

  static boolean jpeg(byte[] b) {
    return b.length > 3 && (b[0] & 0xff) == 0xff && (b[1] & 0xff) == 0xd8 && (b[2] & 0xff) == 0xff;
  }

  static boolean png(byte[] b) {
    return b.length > 8 && (b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
  }

  static boolean webp(byte[] b) {
    return b.length > 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
        && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P';
  }
}
