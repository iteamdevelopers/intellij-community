/*
 * Copyright (c) 2000-2006 JetBrains s.r.o. All Rights Reserved.
 */

package com.intellij.util.xml.highlighting;

import com.intellij.ide.IdeBundle;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.components.ProjectComponent;
import com.intellij.psi.PsiManager;
import com.intellij.psi.util.CachedValue;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.util.containers.WeakValueHashMap;
import com.intellij.util.xml.DomElement;
import com.intellij.util.xml.DomElementVisitor;
import com.intellij.util.xml.DomFileElement;
import com.intellij.util.xml.DomUtil;
import com.intellij.util.xml.reflect.*;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

import java.util.*;

public class DomElementAnnotationsManagerImpl extends DomElementAnnotationsManager implements ProjectComponent {
  private Map<Class, List<DomElementsAnnotator>> myClass2Annotator = new HashMap<Class, List<DomElementsAnnotator>>();

  private Map<DomFileElement, CachedValue<DomElementsProblemsHolder>> myCache =
    new WeakValueHashMap<DomFileElement, CachedValue<DomElementsProblemsHolder>>();
  private static final DomElementsProblemsHolderImpl EMPTY_PROBLEMS_HOLDER = new DomElementsProblemsHolderImpl() {
    public void addProblem(final DomElementProblemDescriptor problemDescriptor) {
      throw new UnsupportedOperationException("This holder is immutable");
    }
  };

  @NotNull
  public DomElementsProblemsHolder getProblemHolder(DomElement element) {
    if (element == null || !element.isValid()) return EMPTY_PROBLEMS_HOLDER;
    return getDomElementsProblemsHolder(element.getRoot());
  }

  @NotNull
  public DomElementsProblemsHolder getCachedProblemHolder(DomElement element) {
    if (element == null || !element.isValid()) return EMPTY_PROBLEMS_HOLDER;
    final DomFileElement<?> fileElement = element.getRoot();
    final CachedValue<DomElementsProblemsHolder> cachedValue = myCache.get(fileElement);
    return cachedValue == null || !cachedValue.hasUpToDateValue() ? EMPTY_PROBLEMS_HOLDER : cachedValue.getValue();
  }

  public List<DomElementProblemDescriptor> getAllProblems(final DomFileElement<?> fileElement, HighlightSeverity minSeverity) {
    return getDomElementsProblemsHolder(fileElement).getAllProblems();
  }

  public DomElementsProblemsHolder getDomElementsProblemsHolder(final DomFileElement<?> fileElement) {
    if (myCache.get(fileElement) == null) {
      myCache.put(fileElement, getCachedValue(fileElement));
    }

    return myCache.get(fileElement).getValue();
  }

  private CachedValue<DomElementsProblemsHolder> getCachedValue(final DomFileElement fileElement) {
    final CachedValuesManager cachedValuesManager = PsiManager.getInstance(fileElement.getManager().getProject()).getCachedValuesManager();

    return cachedValuesManager.createCachedValue(new CachedValueProvider<DomElementsProblemsHolder>() {
      public Result<DomElementsProblemsHolder> compute() {
        final DomElementsProblemsHolder holder = new DomElementsProblemsHolderImpl();
        final DomElement rootElement = fileElement.getRootElement();
        final Class<?> type = DomUtil.getRawType(rootElement.getDomElementType());
        final List<DomElementsAnnotator> list = myClass2Annotator.get(type);
        if (list != null) {
          for (DomElementsAnnotator annotator : list) {
            annotator.annotate(rootElement, holder);
          }
        }
        return new Result<DomElementsProblemsHolder>(holder, fileElement.getFile());
      }
    }, false);
  }


  public void registerDomElementsAnnotator(DomElementsAnnotator annotator, Class aClass) {
    getOrCreateAnnotators(aClass).add(annotator);
  }

  private List<DomElementsAnnotator> getOrCreateAnnotators(final Class aClass) {
    List<DomElementsAnnotator> annotators = myClass2Annotator.get(aClass);
    if (annotators == null) {
      annotators = new ArrayList<DomElementsAnnotator>();
      annotators.add(new MyDomElementsAnnotator());
      myClass2Annotator.put(aClass, annotators);
    }
    return annotators;
  }

  @NotNull
  @NonNls
  public String getComponentName() {
    return "DomElementAnnotationsManager";
  }

  public void initComponent() {

  }

  public void disposeComponent() {

  }

  public void projectOpened() {
  }

  public void projectClosed() {
  }

  private static class MyDomElementsAnnotator implements DomElementsAnnotator {
    public void annotate(DomElement element, final DomElementsProblemsHolder annotator) {
      element.accept(new DomElementVisitor() {
        public void visitDomElement(DomElement element) {
          final DomGenericInfo info = element.getGenericInfo();
          final List<DomChildrenDescription> list = info.getChildrenDescriptions();
          for (final DomChildrenDescription description : list) {
            final List<? extends DomElement> values = description.getValues(element);
            if (description instanceof DomAttributeChildDescription) {
              if (((DomAttributeChildDescription)description).isRequired()) {
                final DomElement child = values.get(0);
                if (child.getXmlElement() == null) {
                  annotator.createProblem(child, IdeBundle.message("attribute.0.should.be.defined", child.getXmlElementName()));
                }
              }
            }
            else if (description instanceof DomFixedChildDescription) {
              final DomFixedChildDescription childDescription = (DomFixedChildDescription)description;
              for (int i = 0; i < values.size(); i++) {
                if (childDescription.isRequired(i)) {
                  final DomElement child = values.get(i);
                  if (child.getXmlElement() == null) {
                    annotator.createProblem(child, IdeBundle.message("child.tag.0.should.be.defined", child.getXmlElementName()));
                  }
                }
              }
            }
            else if (values.isEmpty()) {
              final DomCollectionChildDescription childDescription = (DomCollectionChildDescription)description;
              if (childDescription.isRequiredNotEmpty()) {
                annotator.createProblem(element, childDescription, IdeBundle.message("child.tag.0.should.be.defined", description.getXmlElementName()));
              }
            }
          }
          element.acceptChildren(this);
        }

      });
    }
  }
}
