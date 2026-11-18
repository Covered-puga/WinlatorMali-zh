package com.winlator.cmod.widget;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import androidx.annotation.LayoutRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;
import java.util.function.Function;

/** Renders a label for each item while selections keep the original value. */
public final class LabeledSpinnerAdapter<T> extends ArrayAdapter<T> {
    private final Function<T, String> labelForValue;

    public LabeledSpinnerAdapter(Context context, @LayoutRes int selectedLayout,
                                 List<T> values, Function<T, String> labelForValue) {
        super(context, selectedLayout, values);
        this.labelForValue = labelForValue;
        setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
    }

    @NonNull
    @Override
    public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
        TextView view = (TextView) super.getView(position, convertView, parent);
        view.setText(labelForValue.apply(getItem(position)));
        return view;
    }

    @Override
    public View getDropDownView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
        TextView view = (TextView) super.getDropDownView(position, convertView, parent);
        view.setText(labelForValue.apply(getItem(position)));
        return view;
    }
}
