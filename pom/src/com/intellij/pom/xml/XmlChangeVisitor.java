/*
 * Copyright (c) 2000-2004 by JetBrains s.r.o. All Rights Reserved.
 * Use is subject to license terms.
 */

package com.intellij.pom.xml;

import com.intellij.pom.xml.events.*;

/**
 * @author peter
 */
public interface XmlChangeVisitor {
  void visitXmlAttributeSet(final XmlAttributeSet xmlAttributeSet);

  void visitDocumentChanged(final XmlDocumentChanged xmlDocumentChanged);

  void visitXmlElementChanged(final XmlElementChanged xmlElementChanged);

  void visitXmlTagChildAdd(final XmlTagChildAdd xmlTagChildAdd);

  void visitXmlTagChildChanged(final XmlTagChildChanged xmlTagChildChanged);

  void visitXmlTagChildRemoved(final XmlTagChildRemoved xmlTagChildRemoved);

  void visitXmlTagNameChanged(final XmlTagNameChanged xmlTagNameChanged);

  void visitXmlTextChanged(final XmlTextChanged xmlTextChanged);
}
