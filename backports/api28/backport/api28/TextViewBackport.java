package backport.api28;

import android.graphics.Paint;
import android.widget.TextView;
import backport.Backport;
import backport.Inject;

/** android.widget.TextView, API 28. Same logic as AOSP 9. */
@Backport("android.widget.TextView")
public final class TextViewBackport {
    private TextViewBackport() {}

    @Inject
    public static void setFirstBaselineToTopHeight(TextView self, int firstBaselineToTopHeight) {
        if (firstBaselineToTopHeight < 0) throw new IllegalArgumentException("must be >= 0");
        Paint.FontMetricsInt fm = self.getPaint().getFontMetricsInt();
        int fontMetricsTop = self.getIncludeFontPadding() ? fm.top : fm.ascent;
        // top padding only grows; a smaller value than the font's ascent is a no-op
        if (firstBaselineToTopHeight > Math.abs(fontMetricsTop)) {
            int paddingTop = firstBaselineToTopHeight - (-fontMetricsTop);
            self.setPadding(self.getPaddingLeft(), paddingTop,
                    self.getPaddingRight(), self.getPaddingBottom());
        }
    }

    @Inject
    public static void setLastBaselineToBottomHeight(TextView self, int lastBaselineToBottomHeight) {
        if (lastBaselineToBottomHeight < 0) throw new IllegalArgumentException("must be >= 0");
        Paint.FontMetricsInt fm = self.getPaint().getFontMetricsInt();
        int fontMetricsBottom = self.getIncludeFontPadding() ? fm.bottom : fm.descent;
        if (lastBaselineToBottomHeight > Math.abs(fontMetricsBottom)) {
            int paddingBottom = lastBaselineToBottomHeight - fontMetricsBottom;
            self.setPadding(self.getPaddingLeft(), self.getPaddingTop(),
                    self.getPaddingRight(), paddingBottom);
        }
    }

    @Inject
    public static int getFirstBaselineToTopHeight(TextView self) {
        return self.getPaddingTop() - self.getPaint().getFontMetricsInt().top;
    }

    @Inject
    public static int getLastBaselineToBottomHeight(TextView self) {
        return self.getPaddingBottom() + self.getPaint().getFontMetricsInt().bottom;
    }
}
