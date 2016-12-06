/*
*******************************************************************************
*   Android U2F USB Bridge
*   (c) 2016 Ledger
*
*  Licensed under the Apache License, Version 2.0 (the "License");
*  you may not use this file except in compliance with the License.
*  You may obtain a copy of the License at
*
*      http://www.apache.org/licenses/LICENSE-2.0
*
*   Unless required by applicable law or agreed to in writing, software
*   distributed under the License is distributed on an "AS IS" BASIS,
*   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
*  See the License for the specific language governing permissions and
*   limitations under the License.
********************************************************************************/

package com.ledger.android.u2f.bridge; 

import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.HashMap;

public class TypefaceHelper {

    private static final HashMap<FontStyle, Typeface> sTypefaceCache = new HashMap<>();

    public static void applyFonts(ViewGroup parent) {
        final int childCount = parent.getChildCount();
        for (int childIndex = 0; childIndex < childCount; childIndex++) {
            final View child = parent.getChildAt(childIndex);
            if (child instanceof TextView) {
                applyFont((TextView) child);
            } else if (child instanceof ViewGroup) {
                applyFonts((ViewGroup) child);
            }
        }
    }

    public static void applyFont(TextView textView) {
        if (textView.getTypeface() != null && textView.getTypeface().isBold()) {
            textView.setTypeface(getTypeface(textView.getContext(), FontStyle.BOLD));
        } else {
            textView.setTypeface(getTypeface(textView.getContext(), FontStyle.REGULAR));
        }
    }

    private static Typeface getTypeface(Context context, FontStyle style) {
        if (sTypefaceCache.containsKey(style)) {
            return sTypefaceCache.get(style);
        } else {
            try {
                final Typeface typeface = Typeface.createFromAsset(context.getAssets(), style.getFontPath());
                sTypefaceCache.put(style, typeface);
                return typeface;
            } catch (Exception ex) {
                // Do nothing
                ex.printStackTrace();
            }
        }
        return Typeface.defaultFromStyle(style == FontStyle.BOLD ? Typeface.BOLD : Typeface.NORMAL);
    }

    private enum FontStyle {
        REGULAR("fonts/opensans-regular.ttf"),
        BOLD("fonts/opensans-semibold.ttf");

        private String mFontPath;

        FontStyle(String fontPath) {
            mFontPath = fontPath;
        }

        String getFontPath() {
            return mFontPath;
        }
    }

}
