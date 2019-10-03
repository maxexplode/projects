package com.maxexplode.processor.processor;

import com.google.auto.service.AutoService;
import com.maxexplode.processor.stereotype.SimplePojo;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symtab;
import com.sun.tools.javac.code.TypeTag;
import com.sun.tools.javac.processing.JavacProcessingEnvironment;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.JCExpression;
import com.sun.tools.javac.tree.JCTree.JCMethodDecl;
import com.sun.tools.javac.tree.JCTree.JCVariableDecl;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.tree.TreeTranslator;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.Names;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.util.Set;

@SupportedAnnotationTypes(
    "com.maxexplode.processor.stereotype.SimplePojo")
@SupportedSourceVersion(SourceVersion.RELEASE_8)
@AutoService(Processor.class)
public class ResourceProcessor extends AbstractProcessor {
  private ProcessingEnvironment processingEnvironment;
  private Messager MESSENGER = null;
  private Trees trees;
  private TreeMaker treeMaker;
  private Names names;
  private Context context;

  @Override
  public synchronized void init(ProcessingEnvironment processingEnv) {
    super.init(processingEnv);
    this.processingEnvironment = processingEnv;
    MESSENGER = processingEnvironment.getMessager();
    JavacProcessingEnvironment javacProcessingEnvironment = (JavacProcessingEnvironment) processingEnv;
    trees = Trees.instance(processingEnv);
    context = javacProcessingEnvironment.getContext();
    treeMaker = TreeMaker.instance(context);
    names = Names.instance(context);
  }

  @Override
  public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
    MESSENGER.printMessage(Diagnostic.Kind.OTHER, "Starting instrumentation ...");

    final TreePathScanner<Object, CompilationUnitTree> scanner =
        new TreePathScanner<Object, CompilationUnitTree>() {

          @Override
          public Trees visitClass(
              final ClassTree classTree,
              final CompilationUnitTree unitTree) {

            if (unitTree instanceof JCTree.JCCompilationUnit) {
              final JCTree.JCCompilationUnit compilationUnit = (JCTree.JCCompilationUnit) unitTree;

              if (compilationUnit.sourcefile.getKind() == JavaFileObject.Kind.SOURCE) {
                compilationUnit.accept(new TreeTranslator() {
                  @Override
                  public void visitClassDef(JCTree.JCClassDecl jcClassDecl) {
                    super.visitClassDef(jcClassDecl);

                    List<JCTree> members = jcClassDecl.getMembers();

                    for (JCTree member : members) {
                      if (member instanceof JCVariableDecl) {
                        JCVariableDecl field = (JCVariableDecl) member;
                        List<JCTree.JCMethodDecl> methods = createMethods(field);
                        for (JCMethodDecl jcMethodDecl : methods) {
                          jcClassDecl.defs = jcClassDecl.defs.prepend(jcMethodDecl);
                        }
                      }
                    }
                  }
                });
              }
            }

            return trees;
          }
        };

    for (final Element element : roundEnv.getElementsAnnotatedWith(SimplePojo.class)) {
      final TreePath path = trees.getPath(element);
      scanner.scan(path, path.getCompilationUnit());
    }

    MESSENGER.printMessage(Diagnostic.Kind.OTHER, String
        .format(
            "Finished instrumenting classes : processed {%d} classes",
            roundEnv.getRootElements().size()));
    return true;
  }

  public List<JCTree.JCMethodDecl> createMethods(JCTree.JCVariableDecl field) {
    JCVariableDecl param = treeMaker.Param(names.fromString("_".concat(field.getName().toString())), field.vartype.type, null);
    return List.of(
        treeMaker.MethodDef(
            treeMaker.Modifiers(1),
            names.fromString("get".concat(field.getName().toString())),
            (JCExpression) field.getType(),
            List.nil(),
            List.nil(),
            List.nil(),
            treeMaker.Block(1, createGetterForField(field)),
            null),
        treeMaker.MethodDef(
            treeMaker.Modifiers(1),
            names.fromString("set".concat(field.getName().toString())),
            treeMaker.TypeIdent(TypeTag.VOID),
            List.nil(),
            List.of(param),
            List.nil(),
            treeMaker.Block(0, createSetterForField(
                field,
                param)),
            null)
    );
  }

  public List<JCTree.JCStatement> createGetterForField(JCTree.JCVariableDecl field) {
    return List.of(treeMaker.Return((treeMaker.Ident(field.getName()))));
  }

  public List<JCTree.JCStatement> createSetterForField(
      JCTree.JCVariableDecl field, JCTree.JCVariableDecl parameter) {
    return List.of(treeMaker.Exec(treeMaker.Assign(
        treeMaker.Ident(field),
        treeMaker.Ident(parameter.name))));
  }
}
