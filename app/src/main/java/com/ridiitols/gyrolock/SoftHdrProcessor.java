package com.ridiitols.gyrolock;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import androidx.exifinterface.media.ExifInterface;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/** Gentle single-image HDR-style tone mapping. It lifts shadows and rolls off highlights
 * while adding only a small saturation boost. This is not multi-exposure sensor HDR. */
public final class SoftHdrProcessor {
    private SoftHdrProcessor() {}

    public static boolean apply(File jpeg) {
        if (jpeg == null || !jpeg.isFile() || jpeg.length() <= 0) return false;
        File temp = new File(jpeg.getParentFile(), jpeg.getName() + ".hdrtmp.jpg");
        Bitmap bitmap = null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(jpeg.getAbsolutePath(), bounds);
            long pixels = (long) bounds.outWidth * (long) bounds.outHeight;
            // Avoid crashing low-memory phones on unusually large 48 MP captures.
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || pixels > 18_000_000L) return false;

            ExifInterface originalExif = new ExifInterface(jpeg.getAbsolutePath());
            String orientation = originalExif.getAttribute(ExifInterface.TAG_ORIENTATION);
            String dateTime = originalExif.getAttribute(ExifInterface.TAG_DATETIME);
            String dateTimeOriginal = originalExif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL);
            String make = originalExif.getAttribute(ExifInterface.TAG_MAKE);
            String model = originalExif.getAttribute(ExifInterface.TAG_MODEL);
            String focalLength = originalExif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH);
            String exposureTime = originalExif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME);
            String fNumber = originalExif.getAttribute(ExifInterface.TAG_F_NUMBER);
            String iso = originalExif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY);

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            bitmap = BitmapFactory.decodeFile(jpeg.getAbsolutePath(), opts);
            if (bitmap == null || bitmap.isRecycled()) return false;

            int width = bitmap.getWidth();
            int[] row = new int[width];
            for (int y = 0; y < bitmap.getHeight(); y++) {
                bitmap.getPixels(row, 0, width, 0, y, width, 1);
                for (int x = 0; x < width; x++) {
                    int c = row[x];
                    int a = (c >>> 24) & 0xff;
                    double r = ((c >>> 16) & 0xff) / 255.0;
                    double g = ((c >>> 8) & 0xff) / 255.0;
                    double b = (c & 0xff) / 255.0;
                    double luma = 0.2126 * r + 0.7152 * g + 0.0722 * b;

                    // Gentle shadow lift, fading rapidly in midtones; soft highlight roll-off.
                    double shadowLift = 0.145 * (1.0 - luma) * Math.exp(-luma * 3.8);
                    double highlightRoll = 0.16 * Math.pow(Math.max(0.0, (luma - 0.60) / 0.40), 2.0);
                    double targetLuma = clamp(luma + shadowLift - highlightRoll, 0.0, 1.0);
                    double scale = luma > 0.008 ? targetLuma / luma : 1.0;
                    r = clamp(r * scale, 0.0, 1.0);
                    g = clamp(g * scale, 0.0, 1.0);
                    b = clamp(b * scale, 0.0, 1.0);

                    // Slightly richer color, avoiding a punchy/over-contrasted look.
                    double gray = 0.2126 * r + 0.7152 * g + 0.0722 * b;
                    double sat = 1.075;
                    r = clamp(gray + (r - gray) * sat, 0.0, 1.0);
                    g = clamp(gray + (g - gray) * sat, 0.0, 1.0);
                    b = clamp(gray + (b - gray) * sat, 0.0, 1.0);
                    row[x] = (a << 24) | (((int)Math.round(r * 255)) << 16)
                            | (((int)Math.round(g * 255)) << 8) | (int)Math.round(b * 255);
                }
                bitmap.setPixels(row, 0, width, 0, y, width, 1);
            }

            try (FileOutputStream out = new FileOutputStream(temp)) {
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)) return false;
                out.flush();
                out.getFD().sync();
            }
            ExifInterface processedExif = new ExifInterface(temp.getAbsolutePath());
            copy(processedExif, ExifInterface.TAG_ORIENTATION, orientation);
            copy(processedExif, ExifInterface.TAG_DATETIME, dateTime);
            copy(processedExif, ExifInterface.TAG_DATETIME_ORIGINAL, dateTimeOriginal);
            copy(processedExif, ExifInterface.TAG_MAKE, make);
            copy(processedExif, ExifInterface.TAG_MODEL, model);
            copy(processedExif, ExifInterface.TAG_FOCAL_LENGTH, focalLength);
            copy(processedExif, ExifInterface.TAG_EXPOSURE_TIME, exposureTime);
            copy(processedExif, ExifInterface.TAG_F_NUMBER, fNumber);
            copy(processedExif, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, iso);
            processedExif.saveAttributes();

            File backup = new File(jpeg.getParentFile(), jpeg.getName() + ".hdrbackup");
            if (backup.exists()) backup.delete();
            if (!jpeg.renameTo(backup)) return false;
            if (!temp.renameTo(jpeg)) {
                backup.renameTo(jpeg); // Restore the original if replacement fails.
                return false;
            }
            backup.delete();
            return true;
        } catch (OutOfMemoryError | Exception e) {
            return false;
        } finally {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
            if (temp.exists()) temp.delete();
        }
    }

    private static void copy(ExifInterface exif, String tag, String value) {
        if (value != null) exif.setAttribute(tag, value);
    }
    private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }
}
