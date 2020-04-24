/*
 * Copyright 2018 Andrey Novikov
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Lesser General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License along with
 * this program. If not, see <http://www.gnu.org/licenses/>.
 *
 */

package mobi.maptrek.fragments;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ListFragment;
import android.content.Context;
import android.content.res.Resources;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.CursorAdapter;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import org.oscim.core.GeoPoint;
import org.oscim.theme.IRenderTheme;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;

import mobi.maptrek.Configuration;
import mobi.maptrek.MapHolder;
import mobi.maptrek.MapTrek;
import mobi.maptrek.R;
import mobi.maptrek.databinding.FragmentSearchListBinding;
import mobi.maptrek.maps.maptrek.MapTrekDatabaseHelper;
import mobi.maptrek.maps.maptrek.Tags;
import mobi.maptrek.util.HelperUtils;
import mobi.maptrek.util.JosmCoordinatesParser;
import mobi.maptrek.util.ResUtils;
import mobi.maptrek.util.StringFormatter;

public class TextSearchFragment extends ListFragment implements View.OnClickListener {
    private static final Logger logger = LoggerFactory.getLogger(TextSearchFragment.class);

    public static final String ARG_LATITUDE = "lat";
    public static final String ARG_LONGITUDE = "lon";

    private static final int MSG_CREATE_FTS = 1;
    private static final int MSG_SEARCH = 2;

    private HandlerThread mBackgroundThread;
    private Handler mBackgroundHandler;
    private FragmentHolder mFragmentHolder;
    private MapHolder mMapHolder;
    private OnFeatureActionListener mFeatureActionListener;
    private OnLocationListener mLocationListener;

    private static final String[] mColumns = new String[]{"_id", "name", "kind", "type", "lat", "lon"};

    private boolean mUpdating;
    private SQLiteDatabase mDatabase;
    private IRenderTheme mTheme;
    private CancellationSignal mCancellationSignal;
    private DataListAdapter mAdapter;
    private MatrixCursor mEmptyCursor = new MatrixCursor(mColumns);
    private GeoPoint mCoordinates;
    private CharSequence[] mKinds;
    private int mSelectedKind;
    private HashMap<Integer, Drawable> mTypeIconCache = new HashMap<>();

    private FragmentSearchListBinding mViews;
    private String mText;
    private GeoPoint mFoundPoint;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRetainInstance(true);
        mBackgroundThread = new HandlerThread("SearchThread");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
        mUpdating = false;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        mViews = FragmentSearchListBinding.inflate(inflater, container, false);

        mViews.filterButton.setOnClickListener(this);

        mViews.textEdit.requestFocus();
        mViews.textEdit.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (s.length() == 0) {
                    mAdapter.changeCursor(mEmptyCursor);
                    updateListHeight();
                    mText = null;
                    return;
                }
                mText = s.toString();
                search();
            }
        });

        return mViews.getRoot();
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        Bundle arguments = getArguments();
        double latitude = arguments.getDouble(ARG_LATITUDE);
        double longitude = arguments.getDouble(ARG_LONGITUDE);

        if (savedInstanceState != null) {
            latitude = savedInstanceState.getDouble(ARG_LATITUDE);
            longitude = savedInstanceState.getDouble(ARG_LONGITUDE);
        }

        Activity activity = getActivity();

        mCoordinates = new GeoPoint(latitude, longitude);

        mDatabase = MapTrek.getApplication().getDetailedMapDatabase();

        mAdapter = new DataListAdapter(activity, mEmptyCursor, 0);
        setListAdapter(mAdapter);

        QuickFilterAdapter adapter = new QuickFilterAdapter(activity);
        mViews.quickFilters.setAdapter(adapter);

        LinearLayoutManager horizontalLayoutManager
                = new LinearLayoutManager(activity, LinearLayoutManager.HORIZONTAL, false);
        mViews.quickFilters.setLayoutManager(horizontalLayoutManager);

        Resources resources = activity.getResources();
        String packageName = activity.getPackageName();
        mKinds = new CharSequence[Tags.kinds.length]; // we add two kinds but skip two last
        mKinds[0] = activity.getString(R.string.any);
        mKinds[1] = resources.getString(R.string.kind_place);
        for (int i = 0; i < Tags.kinds.length - 2; i++) { // skip urban and barrier kinds
            int id = resources.getIdentifier(Tags.kinds[i], "string", packageName);
            mKinds[i + 2] = id != 0 ? resources.getString(id) : Tags.kinds[i];
        }

        if (mUpdating || !MapTrekDatabaseHelper.hasFullTextIndex(mDatabase)) {
            mViews.searchFooter.setVisibility(View.GONE);
            mViews.ftsWait.spin();
            mViews.ftsWait.setVisibility(View.VISIBLE);
            mViews.message.setText(R.string.msgWaitForFtsTable);
            mViews.message.setVisibility(View.VISIBLE);

            if (!mUpdating) {
                mUpdating = true;
                final Message m = Message.obtain(mBackgroundHandler, () -> {
                    MapTrekDatabaseHelper.createFtsTable(mDatabase);
                    hideProgress();
                    mUpdating = false;
                });
                m.what = MSG_CREATE_FTS;
                mBackgroundHandler.sendMessage(m);
            } else {
                mBackgroundHandler.post(this::hideProgress);
            }
        } else {
            HelperUtils.showTargetedAdvice(getActivity(), Configuration.ADVICE_TEXT_SEARCH, R.string.advice_text_search, mViews.searchFooter, false);
        }
    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        try {
            mFeatureActionListener = (OnFeatureActionListener) context;
        } catch (ClassCastException e) {
            throw new ClassCastException(context.toString() + " must implement OnFeatureActionListener");
        }
        try {
            mLocationListener = (OnLocationListener) context;
        } catch (ClassCastException e) {
            throw new ClassCastException(context.toString() + " must implement OnLocationListener");
        }
        try {
            mMapHolder = (MapHolder) context;
            mTheme = mMapHolder.getMap().getTheme();
        } catch (ClassCastException e) {
            throw new ClassCastException(context.toString() + " must implement MapHolder");
        }
        mFragmentHolder = (FragmentHolder) context;
    }

    @Override
    public void onDetach() {
        super.onDetach();
        mFragmentHolder = null;
        mFeatureActionListener = null;
        mLocationListener = null;
        mMapHolder = null;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mViews = null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mCancellationSignal != null)
            mCancellationSignal.cancel();
        mBackgroundThread.interrupt();
        mBackgroundHandler.removeCallbacksAndMessages(null);
        mBackgroundThread.quit();
        mBackgroundThread = null;
        Collection<Drawable> drawables = mTypeIconCache.values();
        mTypeIconCache.clear();
        for (Drawable drawable : drawables) {
            if (drawable instanceof BitmapDrawable)
                ((BitmapDrawable)drawable).getBitmap().recycle();
        }
    }

    @Override
    public void onListItemClick(ListView lv, View v, int position, long id) {
        View view = getView();
        if (view != null) {
            // Hide keyboard
            InputMethodManager imm = (InputMethodManager) getActivity().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null)
                imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
        if (id == 0) {
            mMapHolder.setMapLocation(mFoundPoint);
            mLocationListener.showMarkerInformation(mFoundPoint, StringFormatter.coordinates(mFoundPoint));
        } else {
            mFeatureActionListener.onFeatureDetails(id, true);
        }
    }

    private void hideProgress() {
        Activity activity = getActivity();
        if (activity == null)
            return;
        activity.runOnUiThread(() -> {
            mViews.ftsWait.setVisibility(View.GONE);
            mViews.ftsWait.stopSpinning();
            mViews.message.setVisibility(View.GONE);
            mViews.searchFooter.setVisibility(View.VISIBLE);
            HelperUtils.showTargetedAdvice(getActivity(), Configuration.ADVICE_TEXT_SEARCH, R.string.advice_text_search, mViews.searchFooter, false);
        });
    }

    @Override
    public void onClick(View view) {
        if (view != mViews.filterButton)
            return;
        AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
        builder.setSingleChoiceItems(mKinds, mSelectedKind, (dialog, which) -> {
            dialog.dismiss();
            boolean changed = which != mSelectedKind;
            mSelectedKind = which;
            mViews.filterButton.setColorFilter(getActivity().getColor(mSelectedKind > 0 ? R.color.colorAccent : R.color.colorPrimaryDark));
            if (changed && mText != null)
                search();
        });
        AlertDialog dialog = builder.create();
        dialog.show();
    }

    private void search() {
        String[] words = mText.split(" ");
        for (int i = 0; i < words.length; i++) {
            if (words[i].length() > 2)
                words[i] = words[i] + "*";
        }
        final String match = TextUtils.join(" ", words);
        logger.debug("search term: {}", match);
        String kindFilter = "";
        if (mSelectedKind > 0) {
            int mask = mSelectedKind == 1 ? 1 : 1 << (mSelectedKind + 1);
            kindFilter = " AND (kind & " + mask + ") == " + mask;
            logger.debug("kind filter: {}", kindFilter);
        }

        double cos2 = Math.pow(Math.cos(Math.toRadians(mCoordinates.getLatitude())), 2d);
        final String orderBy = " ORDER BY ((lat-(" + mCoordinates.getLatitude() +
                "))*(lat-(" + mCoordinates.getLatitude() + "))+(" + cos2 +
                ")*(lon-(" + mCoordinates.getLongitude()+ "))*(lon-(" +
                mCoordinates.getLongitude()+ "))) ASC";

        final String sql = "SELECT DISTINCT features.id AS _id, kind, type, lat, lon, names.name AS name FROM names_fts" +
                " INNER JOIN names ON (names_fts.docid = names.ref)" +
                " INNER JOIN feature_names ON (names.ref = feature_names.name)" +
                " INNER JOIN features ON (feature_names.id = features.id)" +
                " WHERE names_fts MATCH ? AND (lat != 0 OR lon != 0)" + kindFilter + orderBy +
                " LIMIT 200";
        mViews.filterButton.setImageResource(R.drawable.ic_hourglass_empty);
        mViews.filterButton.setColorFilter(getActivity().getColor(R.color.colorPrimaryDark));
        mViews.filterButton.setOnClickListener(null);
        final Message m = Message.obtain(mBackgroundHandler, () -> {
            if (mCancellationSignal != null)
                mCancellationSignal.cancel();
            mCancellationSignal = new CancellationSignal();
            String[] selectionArgs = {match};
            final Cursor cursor = mDatabase.rawQuery(sql, selectionArgs, mCancellationSignal);
            if (mCancellationSignal.isCanceled()) {
                mCancellationSignal = null;
                return;
            }
            mCancellationSignal = null;
            final Activity activity = getActivity();
            if (activity == null)
                return;
            activity.runOnUiThread(() -> {
                Cursor resultCursor = cursor;
                if (cursor.getCount() == 0) {
                    try {
                        mFoundPoint = JosmCoordinatesParser.parse(mText);
                        MatrixCursor pointCursor = new MatrixCursor(mColumns);
                        pointCursor.addRow(new Object[] {0, 0, mFoundPoint.getLatitude(), mFoundPoint.getLongitude(), StringFormatter.coordinates(mFoundPoint)});
                        resultCursor = pointCursor;
                    } catch (IllegalArgumentException ignore) {
                    }
                }
                mAdapter.changeCursor(resultCursor);
                mViews.filterButton.setImageResource(R.drawable.ic_filter);
                mViews.filterButton.setColorFilter(activity.getColor(mSelectedKind > 0 ? R.color.colorAccent : R.color.colorPrimaryDark));
                mViews.filterButton.setOnClickListener(TextSearchFragment.this);
                updateListHeight();
            });
        });
        m.what = MSG_SEARCH;
        mBackgroundHandler.sendMessage(m);
    }

    private void updateListHeight() {
        ListView listView = getListView();
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) listView.getLayoutParams();
        if (mAdapter.getCount() > 5)
            params.height = (int) (5.5 * getItemHeight());
        else
            params.height = 0;
        listView.setLayoutParams(params);
        mMapHolder.updateMapViewArea();
    }

    public float getItemHeight() {
        TypedValue value = new TypedValue();
        getActivity().getTheme().resolveAttribute(android.R.attr.listPreferredItemHeight, value, true);
        return TypedValue.complexToDimension(value.data, getResources().getDisplayMetrics());
    }

    private Drawable getTypeDrawable(int type) {
        Drawable icon = mTypeIconCache.get(type);
        if (icon == null) {
            icon = Tags.getTypeDrawable(getContext(), mTheme, Math.abs(type));
            if (icon != null)
                mTypeIconCache.put(type, icon);
        }
        return icon;
    }

    private class DataListAdapter extends CursorAdapter {
        private final int mAccentColor;
        private LayoutInflater mInflater;

        DataListAdapter(Context context, Cursor cursor, int flags) {
            super(context, cursor, flags);
            mInflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            mAccentColor = getResources().getColor(R.color.colorAccentLight, context.getTheme());
        }

        @Override
        public View newView(Context context, Cursor cursor, ViewGroup parent) {
            View view = mInflater.inflate(R.layout.list_item_amenity, parent, false);
            if (view != null) {
                ItemHolder holder = new ItemHolder();
                holder.name = view.findViewById(R.id.name);
                holder.distance = view.findViewById(R.id.distance);
                holder.icon = view.findViewById(R.id.icon);
                holder.viewButton = view.findViewById(R.id.view);
                view.setTag(holder);
            }
            return view;
        }

        @Override
        public void bindView(View view, Context context, Cursor cursor) {
            ItemHolder holder = (ItemHolder) view.getTag();

            //long id = cursor.getLong(cursor.getColumnIndex("_id"));
            String name = cursor.getString(cursor.getColumnIndex("name"));
            int kind = cursor.getInt(cursor.getColumnIndex("kind"));
            int type = cursor.getInt(cursor.getColumnIndex("type"));
            float lat = cursor.getFloat(cursor.getColumnIndex("lat"));
            float lon = cursor.getFloat(cursor.getColumnIndex("lon"));

            final GeoPoint coordinates = new GeoPoint(lat, lon);
            double dist = mCoordinates.vincentyDistance(coordinates);
            double bearing = mCoordinates.bearingTo(coordinates);
            String distance = StringFormatter.distanceH(dist) + " " + StringFormatter.angleH(bearing);
            holder.name.setText(name);
            holder.distance.setText(distance);
            holder.viewButton.setOnClickListener(v -> {
                mMapHolder.setMapLocation(coordinates);
                //mFragmentHolder.popAll();
            });

            int color = mAccentColor;
            //color = waypoint.style.color;
            Drawable drawable = getTypeDrawable(type);
            if (drawable != null) {
                holder.icon.setImageDrawable(drawable);
            } else {
                @DrawableRes int icon = ResUtils.getKindIcon(kind);
                if (icon == 0)
                    icon = R.drawable.ic_place;
                holder.icon.setImageResource(icon);
            }
            Drawable background = holder.icon.getBackground().mutate();
            if (background instanceof ShapeDrawable) {
                ((ShapeDrawable) background).getPaint().setColor(color);
            } else if (background instanceof GradientDrawable) {
                ((GradientDrawable) background).setColor(color);
            }
        }
    }

    private static class ItemHolder {
        TextView name;
        TextView distance;
        ImageView icon;
        ImageView viewButton;
    }

    public class QuickFilterAdapter extends RecyclerView.Adapter<QuickFilterAdapter.SimpleViewHolder> {
        private Context context;
        private List<Integer> elements;

        QuickFilterAdapter(Context context) {
            this.context = context;
            this.elements = new ArrayList<>();
            this.elements.add(253); // drinking water
            this.elements.add(259); // toilets
            this.elements.add(130); // emergency phone
            this.elements.add(256); // shelter
            this.elements.add(1); // wilderness hut
            this.elements.add(4); // alpine hut
            this.elements.add(277); // atm
            this.elements.add(280); // bureau de change
            this.elements.add(109); // police
            this.elements.add(124); // pharmacy
            this.elements.add(136); // veterinary
            this.elements.add(238); // fuel
            this.elements.add(-1); // more...
        }

        @NonNull
        @Override
        public SimpleViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            final View view = LayoutInflater.from(context).inflate(R.layout.list_item_quick_filter, parent, false);
            return new SimpleViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull SimpleViewHolder holder, final int position) {
            int type = elements.get(position);
            if (type >= 0)
                holder.button.setImageDrawable(getTypeDrawable(-type)); // we need to separate caches because search result icons are tinted
            else
                holder.button.setImageDrawable(context.getDrawable(R.drawable.ic_more_horiz));

            holder.button.setOnClickListener(view -> {
                if (type >= 0) {
                    mMapHolder.setHighlightedType(type);
                    mFragmentHolder.popCurrent();
                } else {
                    elements.clear();
                    for (int i = 0; i < Tags.typeTags.length; i++)
                        if (Tags.typeTags[i] != null && Tags.typeSelectable[i])
                            elements.add(i);
                    notifyDataSetChanged();
                    mViews.quickFilters.smoothScrollToPosition(0);
                }
            });
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public int getItemCount() {
            return this.elements.size();
        }

        class SimpleViewHolder extends RecyclerView.ViewHolder {
            final ImageButton button;

            SimpleViewHolder(View view) {
                super(view);
                button = view.findViewById(R.id.button);
            }
        }
    }
}
