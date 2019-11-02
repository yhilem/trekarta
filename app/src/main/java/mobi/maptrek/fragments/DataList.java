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

import android.annotation.SuppressLint;
import android.app.ListFragment;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.os.Bundle;
import androidx.annotation.ColorInt;
import androidx.annotation.DrawableRes;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import android.util.SparseBooleanArray;
import android.view.ActionMode;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.AbsListView;
import android.widget.CursorAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import org.oscim.core.GeoPoint;

import java.util.HashSet;

import mobi.maptrek.Configuration;
import mobi.maptrek.DataHolder;
import mobi.maptrek.R;
import mobi.maptrek.data.Track;
import mobi.maptrek.data.Waypoint;
import mobi.maptrek.data.source.DataSource;
import mobi.maptrek.data.source.DataSourceUpdateListener;
import mobi.maptrek.data.source.MemoryDataSource;
import mobi.maptrek.data.source.TrackDataSource;
import mobi.maptrek.data.source.WaypointDataSource;
import mobi.maptrek.data.source.WaypointDbDataSource;
import mobi.maptrek.util.HelperUtils;
import mobi.maptrek.util.JosmCoordinatesParser;
import mobi.maptrek.util.StringFormatter;

public class DataList extends ListFragment implements DataSourceUpdateListener, CoordinatesInputDialog.CoordinatesInputDialogCallback {
    public static final String ARG_LATITUDE = "lat";
    public static final String ARG_LONGITUDE = "lon";
    public static final String ARG_NO_EXTRA_SOURCES = "msg";
    public static final String ARG_HEIGHT = "hgt";
    public static final String ARG_CURRENT_LOCATION = "cur";

    private DataListAdapter mAdapter;
    private DataSource mDataSource;
    private boolean mIsMultiDataSource;
    private OnWaypointActionListener mWaypointActionListener;
    private OnTrackActionListener mTrackActionListener;
    private FragmentHolder mFragmentHolder;
    private DataHolder mDataHolder;
    private FloatingActionButton mFloatingButton;

    private GeoPoint mCoordinates;

    private String mLineSeparator = System.getProperty("line.separator");

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRetainInstance(true);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        final View rootView = inflater.inflate(R.layout.list_with_empty_view, container, false);

        if (HelperUtils.needsTargetedAdvice(Configuration.ADVICE_VIEW_DATA_ITEM)) {
            ViewTreeObserver vto = rootView.getViewTreeObserver();
            vto.addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                @Override
                public void onGlobalLayout() {
                    rootView.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                    if (mAdapter.getCount() > 0) {
                        View view = getListView().getChildAt(0).findViewById(R.id.view);
                        Rect r = new Rect();
                        view.getGlobalVisibleRect(r);
                        HelperUtils.showTargetedAdvice(getActivity(), Configuration.ADVICE_VIEW_DATA_ITEM, R.string.advice_view_data_item, r);
                    }
                }
            });
        }

        return rootView;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mFloatingButton = null;
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        Bundle arguments = getArguments();
        double latitude = arguments.getDouble(ARG_LATITUDE);
        double longitude = arguments.getDouble(ARG_LONGITUDE);
        boolean currentLocation = arguments.getBoolean(ARG_CURRENT_LOCATION);
        boolean noExtraSources = arguments.getBoolean(ARG_NO_EXTRA_SOURCES);
        int minHeight = arguments.getInt(ARG_HEIGHT, 0);

        if (savedInstanceState != null) {
            latitude = savedInstanceState.getDouble(ARG_LATITUDE);
            longitude = savedInstanceState.getDouble(ARG_LONGITUDE);
        }

        mCoordinates = new GeoPoint(latitude, longitude);

        if (currentLocation)
            mDataSource.setReferenceLocation(mCoordinates);
        else
            mDataSource.setReferenceLocation(null);

        TextView emptyView = (TextView) getListView().getEmptyView();
        if (emptyView != null) {
            StringBuilder stringBuilder = new StringBuilder();
            stringBuilder.append(getString(R.string.msgEmptyPlaceList));
            if (noExtraSources) {
                stringBuilder.append(mLineSeparator);
                stringBuilder.append(mLineSeparator);
                stringBuilder.append(getString(R.string.msgNoFileDataSources));
            }
            emptyView.setText(stringBuilder.toString());
        }

        mAdapter = new DataListAdapter(getActivity(), mDataSource.getCursor(), 0);
        setListAdapter(mAdapter);

        ListView listView = getListView();
        listView.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE_MODAL);
        listView.setMultiChoiceModeListener(mMultiChoiceModeListener);

        View rootView = getView();
        if (rootView != null && minHeight > 0)
            rootView.setMinimumHeight(minHeight);

        // If list contains no data footer is not displayed, so we should not worry about
        // message being shown twice
        if (noExtraSources) {
            LayoutInflater inflater = (LayoutInflater) getActivity().getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            if (inflater != null)
                listView.addFooterView(inflater.inflate(R.layout.list_footer_data_source, listView, false), null, false);
        }

        if (mDataSource instanceof WaypointDbDataSource) {
            mFloatingButton = mFragmentHolder.enableListActionButton();
            mFloatingButton.setImageDrawable(getContext().getDrawable(R.drawable.ic_add_location));
            mFloatingButton.setOnClickListener(v -> {
                CoordinatesInputDialog.Builder builder = new CoordinatesInputDialog.Builder();
                CoordinatesInputDialog coordinatesInput = builder.setCallbacks(DataList.this)
                        .setTitle(getString(R.string.titleCoordinatesInput))
                        .create();
                coordinatesInput.show(getFragmentManager(), "pointCoordinatesInput");
            });
        }
    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        try {
            mWaypointActionListener = (OnWaypointActionListener) context;
        } catch (ClassCastException e) {
            throw new ClassCastException(context.toString() + " must implement OnWaypointActionListener");
        }
        try {
            mTrackActionListener = (OnTrackActionListener) context;
        } catch (ClassCastException e) {
            throw new ClassCastException(context.toString() + " must implement OnTrackActionListener");
        }
        try {
            mDataHolder = (DataHolder) context;
        } catch (ClassCastException e) {
            throw new ClassCastException(context.toString() + " must implement DataHolder");
        }
        mFragmentHolder = (FragmentHolder) context;
    }

    @Override
    public void onDetach() {
        super.onDetach();
        mDataSource.removeListener(this);
        mWaypointActionListener = null;
        mTrackActionListener = null;
        mFragmentHolder.disableListActionButton();
        mFragmentHolder = null;
        mDataHolder = null;
    }

    @Override
    public void onResume() {
        super.onResume();
        CoordinatesInputDialog coordinatesInput = (CoordinatesInputDialog) getFragmentManager().findFragmentByTag("pointCoordinatesInput");
        if (coordinatesInput != null) {
            coordinatesInput.setCallback(this);
        }
    }

    @Override
    public void onListItemClick(ListView lv, View v, int position, long id) {
        Cursor cursor = (Cursor) mAdapter.getItem(position);
        int itemType = mDataSource.getDataType(position);
        if (itemType == DataSource.TYPE_WAYPOINT) {
            Waypoint waypoint = ((WaypointDataSource) mDataSource).cursorToWaypoint(cursor);
            mWaypointActionListener.onWaypointDetails(waypoint, true);
        } else if (itemType == DataSource.TYPE_TRACK) {
            Track track = ((TrackDataSource) mDataSource).cursorToTrack(cursor);
            mTrackActionListener.onTrackDetails(track);
        }
    }

    public void setDataSource(DataSource dataSource) {
        mDataSource = dataSource;
        mIsMultiDataSource = mDataSource instanceof WaypointDataSource &&
                mDataSource instanceof TrackDataSource &&
                ((WaypointDataSource) mDataSource).getWaypointsCount() > 0 &&
                ((TrackDataSource) mDataSource).getTracksCount() > 0;
        mDataSource.addListener(this);
    }

    @Override
    public void onDataSourceUpdated() {
        if (mAdapter != null) {
            mAdapter.changeCursor(mDataSource.getCursor());
            mIsMultiDataSource = mDataSource instanceof WaypointDataSource &&
                    mDataSource instanceof TrackDataSource &&
                    ((WaypointDataSource) mDataSource).getWaypointsCount() > 0 &&
                    ((TrackDataSource) mDataSource).getTracksCount() > 0;
        }
    }

    private void shareSelectedItems() {
        HashSet<Waypoint> waypoints = new HashSet<>();
        HashSet<Track> tracks = new HashSet<>();
        populateSelectedItems(waypoints, tracks);
        MemoryDataSource dataSource = new MemoryDataSource();
        dataSource.waypoints.addAll(waypoints);
        dataSource.tracks.addAll(tracks);
        mDataHolder.onDataSourceShare(dataSource);
    }

    private void deleteSelectedItems() {
        HashSet<Waypoint> waypoints = new HashSet<>();
        HashSet<Track> tracks = new HashSet<>();
        populateSelectedItems(waypoints, tracks);
        if (waypoints.size() > 0)
            mWaypointActionListener.onWaypointsDelete(waypoints);
        if (tracks.size() > 0)
            mTrackActionListener.onTracksDelete(tracks);
    }

    private void populateSelectedItems(HashSet<Waypoint> waypoints, HashSet<Track> tracks) {
        SparseBooleanArray positions = getListView().getCheckedItemPositions();
        for (int position = 0; position < mAdapter.getCount(); position++) {
            if (positions.get(position)) {
                Cursor cursor = (Cursor) mAdapter.getItem(position);
                int type = mDataSource.getDataType(position);
                if (type == DataSource.TYPE_WAYPOINT) {
                    Waypoint waypoint = ((WaypointDataSource) mDataSource).cursorToWaypoint(cursor);
                    waypoints.add(waypoint);
                } else if (type == DataSource.TYPE_TRACK) {
                    Track track = ((TrackDataSource) mDataSource).cursorToTrack(cursor);
                    tracks.add(track);
                }
            }
        }
    }

    @Override
    public void onTextInputPositiveClick(String id, String inputText) {
        String[] lines = inputText.split(mLineSeparator);
        boolean errors = false;
        for (String line : lines) {
            if (line.length() == 0)
                continue;
            try {
                JosmCoordinatesParser.Result result = JosmCoordinatesParser.parseWithResult(line);
                String name = null;
                if (result.offset < line.length())
                    name = line.substring(result.offset, line.length()).trim();
                if (name == null || "".equals(name))
                    name = getString(R.string.place_name, Configuration.getPointsCounter());
                mWaypointActionListener.onWaypointCreate(result.coordinates, name, true, false);
            } catch (IllegalArgumentException e) {
                errors = true;
            }
        }
        if (errors)
            HelperUtils.showError(getString(R.string.msgParseMultipleCoordinatesFailed), mFragmentHolder.getCoordinatorLayout());
    }

    @Override
    public void onTextInputNegativeClick(String id) {
    }

    private class DataListAdapter extends CursorAdapter {
        private static final int STATE_UNKNOWN = 0;
        private static final int STATE_SECTIONED_CELL = 1;
        private static final int STATE_REGULAR_CELL = 2;
        @ColorInt
        private int mAccentColor;
        private int[] mCellStates;

        private LayoutInflater mInflater;

        DataListAdapter(Context context, Cursor cursor, int flags) {
            super(context, cursor, flags);
            mInflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            mAccentColor = getResources().getColor(R.color.colorAccentLight, context.getTheme());
            mCellStates = cursor == null ? null : new int[cursor.getCount()];
        }

        @Override
        public void changeCursor(Cursor cursor) {
            super.changeCursor(cursor);
            mCellStates = cursor == null ? null : new int[cursor.getCount()];
        }

        @Override
        public View newView(Context context, Cursor cursor, ViewGroup parent) {
            int viewType = getItemViewType(cursor.getPosition());
            View view = null;
            if (viewType == DataSource.TYPE_WAYPOINT) {
                view = mInflater.inflate(R.layout.list_item_waypoint, parent, false);
            } else if (viewType == DataSource.TYPE_TRACK) {
                view = mInflater.inflate(R.layout.list_item_track, parent, false);
            }
            if (view != null) {
                ItemHolder holder = new ItemHolder();
                holder.separator = view.findViewById(R.id.separator);
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
            final int position = cursor.getPosition();
            int viewType = getItemViewType(position);
            ItemHolder holder = (ItemHolder) view.getTag();

            boolean needSeparator = false;

            switch (mCellStates[position]) {
                case STATE_SECTIONED_CELL:
                    needSeparator = true;
                    break;

                case STATE_REGULAR_CELL:
                    needSeparator = false;
                    break;

                case STATE_UNKNOWN:
                default:
                    if (mIsMultiDataSource && position == 0) {
                        needSeparator = true;
                    } else if (mIsMultiDataSource) {
                        int prevViewType = getItemViewType(position - 1);
                        if (prevViewType != viewType)
                            needSeparator = true;
                    }
                    mCellStates[position] = needSeparator ? STATE_SECTIONED_CELL : STATE_REGULAR_CELL;
                    break;
            }

            if (needSeparator) {
                holder.separator.setText(getText(viewType == DataSource.TYPE_WAYPOINT ? R.string.places : R.string.tracks));
                holder.separator.setVisibility(View.VISIBLE);
            } else {
                holder.separator.setVisibility(View.GONE);
            }

            boolean isChecked = getListView().isItemChecked(position);
            boolean hasChecked = getListView().getCheckedItemCount() > 0;
            @DrawableRes int icon = R.drawable.ic_info_outline;
            @SuppressLint("ResourceAsColor")
            @ColorInt int color = R.color.colorPrimaryDark;

            if (viewType == DataSource.TYPE_WAYPOINT) {
                final Waypoint waypoint = ((WaypointDataSource) mDataSource).cursorToWaypoint(cursor);
                double dist = mCoordinates.vincentyDistance(waypoint.coordinates);
                double bearing = mCoordinates.bearingTo(waypoint.coordinates);
                String distance = StringFormatter.distanceH(dist) + " " + StringFormatter.angleH(bearing);
                holder.name.setText(waypoint.name);
                holder.distance.setText(distance);
                holder.viewButton.setOnClickListener(v -> {
                    mWaypointActionListener.onWaypointView(waypoint);
                    mFragmentHolder.disableListActionButton();
                    mFragmentHolder.popAll();
                });
                icon = R.drawable.ic_point;
                color = waypoint.style.color;
            } else if (viewType == DataSource.TYPE_TRACK) {
                final Track track = ((TrackDataSource) mDataSource).cursorToTrack(cursor);
                String distance = StringFormatter.distanceH(track.getDistance());
                holder.name.setText(track.name);
                holder.distance.setText(distance);
                holder.viewButton.setOnClickListener(v -> {
                    mTrackActionListener.onTrackView(track);
                    mFragmentHolder.disableListActionButton();
                    mFragmentHolder.popAll();
                });
                icon = R.drawable.ic_track;
                color = track.style.color;
            }
            if (hasChecked) {
                holder.viewButton.setVisibility(View.GONE);
            } else {
                holder.viewButton.setVisibility(View.VISIBLE);
            }
            if (isChecked) {
                icon = R.drawable.ic_done;
                color = mAccentColor;
            }
            holder.icon.setImageResource(icon);
            Drawable background = holder.icon.getBackground().mutate();
            if (background instanceof ShapeDrawable) {
                ((ShapeDrawable) background).getPaint().setColor(color);
            } else if (background instanceof GradientDrawable) {
                ((GradientDrawable) background).setColor(color);
            }

        }

        @DataSource.DataType
        @Override
        public int getItemViewType(int position) {
            return mDataSource.getDataType(position);
        }

        @Override
        public int getViewTypeCount() {
            return 2;
        }
    }

    private static class ItemHolder {
        TextView separator;
        TextView name;
        TextView distance;
        ImageView icon;
        ImageView viewButton;
    }

    private AbsListView.MultiChoiceModeListener mMultiChoiceModeListener = new AbsListView.MultiChoiceModeListener() {

        @Override
        public void onItemCheckedStateChanged(ActionMode mode, int position, long id, boolean checked) {
            ListView listView = getListView();
            int count = listView.getCheckedItemCount();
            mode.setTitle(getResources().getQuantityString(R.plurals.itemsSelected, count, count));
            // Update (redraw) list item view
            int start = listView.getFirstVisiblePosition();
            for (int i = start, j = listView.getLastVisiblePosition(); i <= j; i++) {
                if (position == i) {
                    View view = listView.getChildAt(i - start);
                    listView.getAdapter().getView(i, view, listView);
                    break;
                }
            }
        }

        @Override
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            switch (item.getItemId()) {
                case R.id.action_share:
                    shareSelectedItems();
                    mode.finish();
                    return true;
                case R.id.action_delete:
                    deleteSelectedItems();
                    mode.finish();
                    return true;
                default:
                    return false;
            }
        }

        @Override
        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            MenuInflater inflater = mode.getMenuInflater();
            inflater.inflate(R.menu.context_menu_waypoint_list, menu);
            if (mFloatingButton != null)
                mFloatingButton.setVisibility(View.GONE);
            return true;
        }

        @Override
        public void onDestroyActionMode(ActionMode mode) {
            if (mFloatingButton != null)
                mFloatingButton.setVisibility(View.VISIBLE);
        }

        @Override
        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            return false;
        }
    };
}
