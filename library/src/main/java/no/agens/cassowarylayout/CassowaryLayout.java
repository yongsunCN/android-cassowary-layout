/*
 * Copyright (C) 2006 The Android Open Source Project
 * Copyright (C) 2014 Agens AS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package no.agens.cassowarylayout;

import android.content.Context;
import android.content.res.TypedArray;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;


import org.pybee.cassowary.Variable;

import java.util.ArrayList;

import no.agens.cassowarylayout.util.MeasureSpecUtils;
import no.agens.cassowarylayout.util.TimerUtil;

public class CassowaryLayout extends ViewGroup  {

    private String logTag;

    private volatile CassowaryModel cassowaryModel;

    private ViewIdResolver viewIdResolver;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private volatile Thread setupThread;

    private float preSetupWidthHeightRatio = 1;

    public interface CassowaryLayoutSetupCallback {
        void onCassowaryLayoutSetupComplete(CassowaryLayout layout);
    }

    private ArrayList<CassowaryLayoutSetupCallback> setupObservers;

    public CassowaryLayout(Context context, ViewIdResolver viewIdResolver) {
        super(context);
        this.viewIdResolver = viewIdResolver;
        this.cassowaryModel = new CassowaryModel(context);
    }

    public CassowaryLayout(Context context, ViewIdResolver viewIdResolver, CassowaryModel cassowaryModel) {
        this(context, viewIdResolver);
        this.cassowaryModel = cassowaryModel;
    }

    public CassowaryLayout(Context context) {
        super(context);
        this.viewIdResolver = new DefaultViewIdResolver(getContext());
        this.cassowaryModel = new CassowaryModel(context);
    }

    public CassowaryLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        this.viewIdResolver = new DefaultViewIdResolver(getContext());
        readConstraintsFromXml(attrs);
    }

    public CassowaryLayout(Context context, AttributeSet attrs,
                          int defStyle) {
        super(context, attrs, defStyle);
        this.viewIdResolver = new DefaultViewIdResolver(getContext());
        readConstraintsFromXml(attrs);
    }

    @Override
    public void onDetachedFromWindow() {
        setupThread = null;
    }

    public CassowaryModel getCassowaryModel() {
        return cassowaryModel;
    }


    public void addSetupCallback(final CassowaryLayoutSetupCallback setupObserver) {

        if (isSetupComplete()) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    setupObserver.onCassowaryLayoutSetupComplete(CassowaryLayout.this);
                }
            });
        } else {
            if (setupObservers == null) {
                setupObservers = new ArrayList<CassowaryLayoutSetupCallback>();
            }
            setupObservers.add(setupObserver);
        }
    }

    private void callbackAfterSetup() {
        if (setupObservers != null) {
            for (CassowaryLayoutSetupCallback observer : setupObservers) {
                observer.onCassowaryLayoutSetupComplete(this);
            }
            setupObservers.clear();
        }
    }


    public void setupSolverAsync(final CharSequence[] constraints) {
        final long timeBefore = System.nanoTime();

        setupThread = new Thread(new Runnable() {
            @Override
            public void run() {

                log("thread took " + TimerUtil.since(timeBefore) + " to start");

                final long setupStart = System.nanoTime();

                doSetupSolver(constraints);

                log("creation took " + TimerUtil.since(setupStart));

                final long setupEnd = System.nanoTime();

                if (setupThread != null) {
                    handler.postAtFrontOfQueue(new Runnable() {
                        @Override
                        public void run() {
                            log("post to main thread took " + TimerUtil.since(setupEnd));
                            setupThread = null;
                            requestLayout();
                            callbackAfterSetup();

                        }
                    });
                } // else setupThread was set to null by onDetachWindow
            }
        });
        setupThread.start();

    }

    private void doSetupSolver(CharSequence[] constraints) {
        CassowaryModel cassowaryModel = new CassowaryModel(getContext().getApplicationContext());
        cassowaryModel.addConstraints(constraints);
        // don't set this.cassowaryModel until after constraints have been added because it's existence indicates that setup is complete
        this.cassowaryModel = cassowaryModel;
    }

    private void updateIntrinsicWidthConstraints() {
        long timeBeforeSolve = System.nanoTime();
        int count = getChildCount();
        for (int i = 0; i < count; i++) {
            View child = getChildAt(i);

            if (child.getVisibility() != GONE) {
                Node node = getNodeById(child.getId());
                if (node.hasIntrinsicWidth()) {
                    int childWidth = child.getMeasuredWidth();
                    log("child " + viewIdResolver.getViewNameById(child.getId()) + " intrinsic width " + childWidth);
                    if ((int)node.getIntrinsicWidth().value() != childWidth) {
                        node.setIntrinsicWidth(childWidth);
                    }

                }
            }
        }
        log("updateIntrinsicWidthConstraints took " +  TimerUtil.since(timeBeforeSolve));
    }

    private void updateIntrinsicHeightConstraints() {

       long timeBeforeSolve = System.nanoTime();

       int count = getChildCount();
        for (int i = 0; i < count; i++) {
            View child = getChildAt(i);

            if (child.getVisibility() != GONE) {
                Node node = getNodeById(child.getId());

                if (node.hasIntrinsicHeight()) {
                    int childHeight = child.getMeasuredHeight();
                    Variable intrinsicHeight = node.getIntrinsicHeight();
                    log("child " + viewIdResolver.getViewNameById(child.getId()) + " intrinsic height (measured) " + childHeight);
                    if ((int)intrinsicHeight.value() != childHeight) {
                        long timeBeforeGetMeasuredHeight = System.nanoTime();

                        node.setIntrinsicHeight(childHeight);
                        log("node.setIntrinsicHeight took " +  TimerUtil.since(timeBeforeGetMeasuredHeight));
                    }
                }

            }

        }
        log("updateIntrinsicHeightConstraints took " +  TimerUtil.since(timeBeforeSolve));
    }

    private void setAllChildViewsTo(int visibility) {
        final int size = getChildCount();

        for (int i = 0; i < size; ++i) {
            final View child = getChildAt(i);
            child.setVisibility(visibility);
        }

    }

    protected void measureChildrenUsingNodes(int parentWidthMode, int parentHeightMode) {
        long timeBeforeSolve = System.nanoTime();

        final int size = getChildCount();

        for (int i = 0; i < size; ++i) {
            final View child = getChildAt(i);
            if (child.getVisibility() != GONE) {

                Node node = getNodeById(child.getId());

                int nodeHeight = (int) node.getVariableValue(Node.HEIGHT);
                int nodeWidth = (int) node.getVariableValue(Node.WIDTH);

                int widthMode = parentWidthMode;
                if (node.hasIntrinsicWidth()) {
                    widthMode = MeasureSpec.UNSPECIFIED;
                    nodeWidth = 0;
                }

                int heightMode = parentHeightMode;
                if (node.hasIntrinsicHeight()) {
                    heightMode = MeasureSpec.UNSPECIFIED;
                    nodeHeight = 0;
                }

                // If the parent's width is unspecified, infer it from the container node
                if (parentWidthMode == MeasureSpec.UNSPECIFIED) {
                    widthMode = MeasureSpec.AT_MOST;
                    nodeWidth = (int) cassowaryModel.getContainerNode().getWidth().value();
                }


                int childHeightSpec = MeasureSpec.makeMeasureSpec(nodeHeight, heightMode);
                int childWidthSpec = MeasureSpec.makeMeasureSpec(nodeWidth, widthMode);

                log("child " + viewIdResolver.getViewNameById(child.getId()) + " width " + MeasureSpecUtils.getModeAsString(childWidthSpec) + " " + nodeWidth + " height " + MeasureSpecUtils.getModeAsString(childHeightSpec) + " " + nodeHeight);
                measureChild(child, childWidthSpec, childHeightSpec);
            }
        }
        log("measureChildrenUsingNodes took " + TimerUtil.since(timeBeforeSolve));
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        boolean isSetupComplete = isSetupComplete();
        log("onMeasure setup complete " + isSetupComplete);
        if (isSetupComplete) {
            postSetupOnMeasure(widthMeasureSpec, heightMeasureSpec);
        } else {
            preSetupOnMeasure(widthMeasureSpec, heightMeasureSpec);
        }
    }

    protected void postSetupOnMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        long timeBeforeSolve = System.nanoTime();

        int width =  MeasureSpec.getSize(widthMeasureSpec);
        int height =  MeasureSpec.getSize(heightMeasureSpec);

        log("postSetupOnMeasure width " +
           MeasureSpecUtils.getModeAsString(widthMeasureSpec) + " " +
                width + " height " +
           MeasureSpecUtils.getModeAsString(heightMeasureSpec) + " " +
                height);


        int resolvedWidth = -1;
        int resolvedHeight = -1;

        int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        if (heightMode == MeasureSpec.AT_MOST) {
            cassowaryModel.getContainerNode().setVariableToAtMost(Node.HEIGHT, height - getPaddingTop() - getPaddingBottom());
        } else if (heightMode == MeasureSpec.EXACTLY) {
            cassowaryModel.getContainerNode().setVariableToValue(Node.HEIGHT, height - getPaddingTop() - getPaddingBottom());
        }
        int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        if (widthMode == MeasureSpec.AT_MOST) {
            cassowaryModel.getContainerNode().setVariableToAtMost(Node.WIDTH, width - getPaddingLeft() - getPaddingRight());
        } else {
            cassowaryModel.getContainerNode().setVariableToValue(Node.WIDTH, width - getPaddingLeft() - getPaddingRight());
        }

        cassowaryModel.solve();

        measureChildrenUsingNodes(widthMode, heightMode);

        updateIntrinsicWidthConstraints();
        updateIntrinsicHeightConstraints();

        cassowaryModel.solve();

        if (widthMode == MeasureSpec.AT_MOST || widthMode == MeasureSpec.UNSPECIFIED) {
            resolvedWidth = (int) cassowaryModel.getContainerNode().getWidth().value() + getPaddingLeft() + getPaddingRight();
        }

        if (heightMode == MeasureSpec.AT_MOST || heightMode == MeasureSpec.UNSPECIFIED) {
            resolvedHeight = (int) cassowaryModel.getContainerNode().getHeight().value() + getPaddingTop() + getPaddingBottom();
        }

        setMeasuredDimension(resolvedWidth, resolvedHeight);

        log("onMeasure took " + TimerUtil.since(timeBeforeSolve));
    }


    protected void preSetupOnMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width =  MeasureSpec.getSize(widthMeasureSpec);
        int height =  MeasureSpec.getSize(heightMeasureSpec);

        log("preSetupOnMeasure width " +
                MeasureSpecUtils.getModeAsString(widthMeasureSpec) + " " +
                width + " height " +
                MeasureSpecUtils.getModeAsString(heightMeasureSpec) + " " +
                height);

        int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        int heightMode = MeasureSpec.getMode(heightMeasureSpec);

        int widthBasedOnRatio = (int)(height * getPreSetupWidthHeightRatio());

        int heightBasedOnRatio = (int)(width / getPreSetupWidthHeightRatio());

        switch (widthMode) {
            case MeasureSpec.AT_MOST:
                if (heightMode == MeasureSpec.EXACTLY) {
                    if (widthBasedOnRatio < width) {
                        width = widthBasedOnRatio;
                    }
                }
                break;
            case MeasureSpec.EXACTLY:
                // do nothing
                break;
            case MeasureSpec.UNSPECIFIED:
                width = widthBasedOnRatio;
                break;
        }

        switch (heightMode) {
            case MeasureSpec.AT_MOST:
                if (widthMode == MeasureSpec.EXACTLY) {
                    if (heightBasedOnRatio < height) {
                        height = heightBasedOnRatio;
                    }
                }
                break;
            case MeasureSpec.EXACTLY:
                // do nothing
                break;
            case MeasureSpec.UNSPECIFIED:
                height = heightBasedOnRatio;
                break;
        }

        setMeasuredDimension(width, height);
    }

    /**
     * Returns a set of layout parameters with a width of
     * {@link android.view.ViewGroup.LayoutParams#WRAP_CONTENT},
     * a height of {@link android.view.ViewGroup.LayoutParams#WRAP_CONTENT}
     * and with the coordinates (0, 0).
     */
    @Override
    protected ViewGroup.LayoutParams generateDefaultLayoutParams() {
        return new LayoutParams();
    }

    @Override
    protected void onLayout(boolean changed, int l, int t,
                            int r, int b) {
        if (isSetupComplete()) {
            postSetupOnLayout(changed, l, t, r, b);
        }

    }

    protected void postSetupOnLayout(boolean changed, int l, int t,
                            int r, int b) {

        long timeBeforeSolve = System.nanoTime();

        cassowaryModel.solve();

        log(
                       " container height " + cassowaryModel.getContainerNode().getHeight().value() +
                       " container width " + cassowaryModel.getContainerNode().getWidth().value() +
                       " container center x " + cassowaryModel.getContainerNode().getCenterX().value() +
                       " container center y " + cassowaryModel.getContainerNode().getCenterY().value()
                );
        int count = getChildCount();

        for (int i = 0; i < count; i++) {
            View child = getChildAt(i);
            if (child.getVisibility() != GONE) {

                CassowaryLayout.LayoutParams lp =
                        (CassowaryLayout.LayoutParams) child.getLayoutParams();

                int childId = child.getId();
                Node node = getNodeById(childId);

                int x = (int) node.getLeft().value() + getPaddingLeft();
                int y = (int) node.getTop().value() + getPaddingTop();

                int width = (int) node.getWidth().value();
                int height = (int) node.getHeight().value();
                log("child " + viewIdResolver.getViewNameById(child.getId())  + " x " + x + " y " + y + " width " + width + " height " + height);

                if (node.hasIntrinsicHeight()) {
                    log("child " + viewIdResolver.getViewNameById(child.getId())  + " intrinsic height " + node.getIntrinsicHeight().value());
                }

                if (node.hasVariable(Node.CENTERX)) {
                    log("child " + viewIdResolver.getViewNameById(child.getId())  + " centerX " + node.getVariable(Node.CENTERX).value());
                }

                if (node.hasVariable(Node.CENTERY)) {
                    log("child " + viewIdResolver.getViewNameById(child.getId())  + " centerY " + node.getVariable(Node.CENTERY).value());
                }

                child.layout(x, y,
                        x + /*child.getMeasuredWidth()*/ width ,
                        y + /*child.getMeasuredHeight() */+ height);

            }
        }
        log("onLayout - took " + TimerUtil.since(timeBeforeSolve));
    }

    public void setChildPositionsFromCassowaryModel() {
        long timeBeforeSolve = System.nanoTime();
        int count = getChildCount();
        for (int i = 0; i < count; i++) {
            View child = getChildAt(i);
            if (child.getVisibility() != GONE) {

                int childId = child.getId();

                Node node = getNodeById(childId);

                int x = (int) node.getLeft().value() + getPaddingLeft();
                int y = (int) node.getTop().value() + getPaddingTop();

                child.setX(x);
                child.setY(y);

            }
        }
        log("setChildPositionsFromCassowaryModel - took " + TimerUtil.since(timeBeforeSolve));
    }

    @Override
    public ViewGroup.LayoutParams generateLayoutParams(AttributeSet attrs) {
        return new CassowaryLayout.LayoutParams(getContext(), attrs);
    }

    // Override to allow type-checking of CassowaryLayout.
    @Override
    protected boolean checkLayoutParams(ViewGroup.LayoutParams p) {
        return p instanceof CassowaryLayout.LayoutParams;
    }

    @Override
    protected ViewGroup.LayoutParams generateLayoutParams(ViewGroup.LayoutParams p) {
        return new LayoutParams(p);
    }

    @Override
    public boolean shouldDelayChildPressedState() {
        return false;
    }


    public static class LayoutParams extends ViewGroup.LayoutParams {

        public LayoutParams() {
            super(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        }

        public LayoutParams(Context c, AttributeSet attrs) {
            super(c, attrs);
            TypedArray a = c.obtainStyledAttributes(attrs,
                    R.styleable.AbsoluteLayout_Layout);
            a.recycle();
        }

        /**
         * {@inheritDoc}
         */
        public LayoutParams(ViewGroup.LayoutParams source) {
            super(source);
        }

        public String debug(String output) {
            return "";
        }
    }

    private void readConstraintsFromXml(AttributeSet attrs) {
        TypedArray a = getContext().getTheme().obtainStyledAttributes(
                attrs,
                R.styleable.CassowaryLayout,
                0, 0);

        try {
            final CharSequence[] constraints = a.getTextArray(R.styleable.CassowaryLayout_constraints);

            boolean asyncSetup = a.getBoolean(R.styleable.CassowaryLayout_asyncSetup, true);

            preSetupWidthHeightRatio = a.getFloat(R.styleable.CassowaryLayout_preSetupWidthHeightRatio, preSetupWidthHeightRatio);

            if (asyncSetup) {
                setupSolverAsync(constraints);
            } else {
                doSetupSolver(constraints);
            }

            if (constraints == null) {
                throw new RuntimeException("missing cassowary:constraints attribute in XML");
            }

        } finally {
            a.recycle();
        }

    }

    public Node getNodeById(int id) {
        if (!isSetupComplete()) {
            throw new RuntimeException("use a CassowaryLayoutSetupCallback to wait for setup to complete before calling getNodeById");
        }
        Node node = cassowaryModel.getNodeByName(viewIdResolver.getViewNameById(id));
        return node;
    }


    private void log(String message) {
        if (logTag == null) {
            try {
                logTag = "CassowaryLayout " + viewIdResolver.getViewNameById(getId());
            } catch (RuntimeException e) {
                logTag = "CassowaryLayout noid";
            }
        }
        Log.d(logTag, message);
    }

    private boolean isSetupComplete() {

        boolean isSetupComplete = cassowaryModel != null;
        log("setup complete " + isSetupComplete);
        return isSetupComplete;

    }

    public float getPreSetupWidthHeightRatio() {
        return preSetupWidthHeightRatio;
    }

    public void setPreSetupWidthHeightRatio(float preSetupWidthHeightRatio) {
        this.preSetupWidthHeightRatio = preSetupWidthHeightRatio;
    }
}

