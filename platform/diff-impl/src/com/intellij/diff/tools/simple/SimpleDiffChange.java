/*
 * Copyright 2000-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.diff.tools.simple;

import com.intellij.diff.fragments.DiffFragment;
import com.intellij.diff.fragments.LineFragment;
import com.intellij.diff.util.DiffDrawUtil;
import com.intellij.diff.util.DiffUtil;
import com.intellij.diff.util.Side;
import com.intellij.diff.util.TextDiffType;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.editor.markup.*;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.ArrayList;
import java.util.List;

public class SimpleDiffChange {
  @NotNull private final SimpleDiffViewer myViewer;

  @NotNull private final LineFragment myFragment;
  @Nullable private final List<DiffFragment> myInnerFragments;

  @Nullable private final EditorEx myEditor1;
  @Nullable private final EditorEx myEditor2;

  @NotNull private final List<RangeHighlighter> myHighlighters = new ArrayList<RangeHighlighter>();
  @NotNull private final List<MyGutterOperation> myOperations = new ArrayList<MyGutterOperation>();

  private boolean myIsValid = true;
  private int[] myLineStartShifts = new int[2];
  private int[] myLineEndShifts = new int[2];

  // TODO: adjust color from inner fragments - configurable
  public SimpleDiffChange(@NotNull SimpleDiffViewer viewer,
                          @NotNull LineFragment fragment,
                          @Nullable EditorEx editor1,
                          @Nullable EditorEx editor2,
                          boolean inlineHighlight) {
    myViewer = viewer;

    myFragment = fragment;
    myInnerFragments = inlineHighlight ? fragment.getInnerFragments() : null;

    myEditor1 = editor1;
    myEditor2 = editor2;

    installHighlighter();
  }

  public void installHighlighter() {
    assert myHighlighters.isEmpty();

    if (myInnerFragments != null) {
      doInstallHighlighterWithInner();
    }
    else {
      doInstallHighlighterSimple();
    }
    doInstallActionHighlighters();
  }

  public void destroyHighlighter() {
    for (RangeHighlighter highlighter : myHighlighters) {
      highlighter.dispose();
    }
    myHighlighters.clear();

    for (MyGutterOperation operation : myOperations) {
      operation.dispose();
    }
    myOperations.clear();
  }

  private void doInstallHighlighterSimple() {
    createHighlighter(Side.LEFT, false);
    createHighlighter(Side.RIGHT, false);
  }

  private void doInstallHighlighterWithInner() {
    assert myInnerFragments != null;

    createHighlighter(Side.LEFT, true);
    createHighlighter(Side.RIGHT, true);

    for (DiffFragment fragment : myInnerFragments) {
      createInlineHighlighter(fragment, Side.LEFT);
      createInlineHighlighter(fragment, Side.RIGHT);
    }
  }

  private void doInstallActionHighlighters() {
    if (myEditor1 != null && myEditor2 != null) {
      myOperations.add(createOperation(Side.LEFT));
      myOperations.add(createOperation(Side.RIGHT));
    }
  }

  private void createHighlighter(@NotNull Side side, boolean ignored) {
    Editor editor = side.select(myEditor1, myEditor2);
    if (editor == null) return;

    int start = side.getStartOffset(myFragment);
    int end = side.getEndOffset(myFragment);
    TextDiffType type = DiffUtil.getLineDiffType(myFragment);

    myHighlighters.add(DiffDrawUtil.createHighlighter(editor, start, end, type, ignored));

    int startLine = side.getStartLine(myFragment);
    int endLine = side.getEndLine(myFragment);

    if (startLine == endLine) {
      if (startLine != 0) myHighlighters.add(DiffDrawUtil.createLineMarker(editor, endLine - 1, type, SeparatorPlacement.BOTTOM, true));
    }
    else {
      myHighlighters.add(DiffDrawUtil.createLineMarker(editor, startLine, type, SeparatorPlacement.TOP));
      myHighlighters.add(DiffDrawUtil.createLineMarker(editor, endLine - 1, type, SeparatorPlacement.BOTTOM));
    }
  }

  private void createInlineHighlighter(@NotNull DiffFragment fragment, @NotNull Side side) {
    Editor editor = side.select(myEditor1, myEditor2);
    if (editor == null) return;

    int start = side.getStartOffset(fragment);
    int end = side.getEndOffset(fragment);
    TextDiffType type = DiffUtil.getDiffType(fragment);

    int startOffset = side.getStartOffset(myFragment);
    start += startOffset;
    end += startOffset;

    RangeHighlighter highlighter = DiffDrawUtil.createInlineHighlighter(editor, start, end, type);
    myHighlighters.add(highlighter);
  }

  public void updateGutterActions(boolean force) {
    for (MyGutterOperation operation : myOperations) {
      operation.update(force);
    }
  }

  //
  // Getters
  //

  public int getStartLine(@NotNull Side side) {
    return side.getStartLine(myFragment) + side.select(myLineStartShifts);
  }

  public int getEndLine(@NotNull Side side) {
    return side.getEndLine(myFragment) + side.select(myLineEndShifts);
  }

  @NotNull
  public TextDiffType getDiffType() {
    return DiffUtil.getLineDiffType(myFragment);
  }

  public boolean isValid() {
    return myIsValid;
  }

  //
  // Shift
  //

  public boolean processChange(int oldLine1, int oldLine2, int shift, @NotNull Side side) {
    int line1 = getStartLine(side);
    int line2 = getEndLine(side);

    if (line2 <= oldLine1) return false;
    if (line1 >= oldLine2) {
      myLineStartShifts[side.getIndex()] += shift;
      myLineEndShifts[side.getIndex()] += shift;
      return false;
    }

    if (line1 <= oldLine1 && line2 >= oldLine2) {
      myLineEndShifts[side.getIndex()] += shift;
      return false;
    }

    for (MyGutterOperation operation : myOperations) {
      operation.dispose();
    }
    myOperations.clear();

    myIsValid = false;
    return true;
  }

  //
  // Change applying
  //

  public boolean isSelectedByLine(int line, @NotNull Side side) {
    if (myEditor1 == null || myEditor2 == null) return false;

    int line1 = getStartLine(side);
    int line2 = getEndLine(side);

    return DiffUtil.isSelectedByLine(line, line1, line2);
  }

  //
  // Helpers
  //

  @NotNull
  private MyGutterOperation createOperation(@NotNull Side side) {
    assert myEditor1 != null && myEditor2 != null;
    int offset = side.getStartOffset(myFragment);
    EditorEx editor = side.select(myEditor1, myEditor2);
    RangeHighlighter highlighter = editor.getMarkupModel().addRangeHighlighter(offset, offset,
                                                                               HighlighterLayer.ADDITIONAL_SYNTAX,
                                                                               null,
                                                                               HighlighterTargetArea.LINES_IN_RANGE);
    return new MyGutterOperation(side, highlighter);
  }

  private class MyGutterOperation {
    @NotNull private final Side mySide;
    @NotNull private final RangeHighlighter myHighlighter;

    private boolean myCtrlPressed;
    private boolean myShiftPressed;

    private MyGutterOperation(@NotNull Side side, @NotNull RangeHighlighter highlighter) {
      mySide = side;
      myHighlighter = highlighter;

      update(true);
    }

    public void dispose() {
      myHighlighter.dispose();
    }

    public void update(boolean force) {
      if (!force && !areModifiersChanged()) {
        return;
      }
      if (myHighlighter.isValid()) myHighlighter.setGutterIconRenderer(createRenderer());
    }

    private boolean areModifiersChanged() {
      return myCtrlPressed != myViewer.getModifierProvider().isCtrlPressed() ||
             myShiftPressed != myViewer.getModifierProvider().isShiftPressed();
    }

    @Nullable
    public GutterIconRenderer createRenderer() {
      assert myEditor1 != null && myEditor2 != null;

      myCtrlPressed = myViewer.getModifierProvider().isCtrlPressed();
      myShiftPressed = myViewer.getModifierProvider().isShiftPressed();

      boolean isEditable = DiffUtil.isEditable(mySide.select(myEditor1, myEditor2));
      boolean isOtherEditable = DiffUtil.isEditable(mySide.other().select(myEditor1, myEditor2));
      boolean isAppendable = myFragment.getStartLine1() != myFragment.getEndLine1() &&
                             myFragment.getStartLine2() != myFragment.getEndLine2();

      if ((myShiftPressed || !isOtherEditable) && isEditable) {
        return createRevertRenderer(mySide);
      }
      if (myCtrlPressed && isAppendable) {
        return createAppendRenderer(mySide);
      }
      return createApplyRenderer(mySide);
    }
  }

  @Nullable
  private GutterIconRenderer createApplyRenderer(@NotNull final Side side) {
    return createIconRenderer(side, "Replace", AllIcons.Diff.Arrow, new Runnable() {
      @Override
      public void run() {
        myViewer.replaceChange(SimpleDiffChange.this, side);
      }
    });
  }

  @Nullable
  private GutterIconRenderer createAppendRenderer(@NotNull final Side side) {
    return createIconRenderer(side, "Insert", AllIcons.Diff.ArrowLeftDown, new Runnable() {
      @Override
      public void run() {
        myViewer.appendChange(SimpleDiffChange.this, side);
      }
    });
  }

  @Nullable
  private GutterIconRenderer createRevertRenderer(@NotNull final Side side) {
    return createIconRenderer(side.other(), "Revert", AllIcons.Diff.Remove, new Runnable() {
      @Override
      public void run() {
        myViewer.replaceChange(SimpleDiffChange.this, side.other());
      }
    });
  }

  @Nullable
  private GutterIconRenderer createIconRenderer(@NotNull final Side sourceSide,
                                                @NotNull final String tooltipText,
                                                @NotNull final Icon icon,
                                                @NotNull final Runnable perform) {
    assert myEditor1 != null && myEditor2 != null;
    if (!DiffUtil.isEditable(sourceSide.other().select(myEditor1, myEditor2))) return null;
    return new GutterIconRenderer() {
      @NotNull
      @Override
      public Icon getIcon() {
        return icon;
      }

      public boolean isNavigateAction() {
        return true;
      }

      @Nullable
      @Override
      public AnAction getClickAction() {
        return new DumbAwareAction() {
          @Override
          public void actionPerformed(AnActionEvent e) {
            final Project project = e.getProject();
            final Document document1 = myEditor1.getDocument();
            final Document document2 = myEditor2.getDocument();

            if (!myIsValid) return;

            DiffUtil.executeWriteCommand(sourceSide.other().select(document1, document2), project, "Replace change", new Runnable() {
              @Override
              public void run() {
                perform.run();
              }
            });
          }
        };
      }

      @Override
      public boolean equals(Object obj) {
        return obj == this;
      }

      @Override
      public int hashCode() {
        return System.identityHashCode(this);
      }

      @Nullable
      @Override
      public String getTooltipText() {
        return tooltipText;
      }
    };
  }
}
