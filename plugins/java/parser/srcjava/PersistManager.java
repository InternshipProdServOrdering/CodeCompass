package parser.srcjava;

import model.*;
import model.enums.AstType;
import model.enums.MemberTypeKind;
import model.enums.SymbolType;
import model.enums.Visibility;
import org.eclipse.jdt.core.dom.*;

import javax.persistence.EntityManager;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.function.Consumer;

import static parser.srcjava.Utils.*;

public class PersistManager {
  private final CompilationUnit cu;
  private final EntityManager em;
  private final QueryManager qm;
  private final long fileId;

  public PersistManager(CompilationUnit cu, EntityManager em, long fileId) {
    this.cu = cu;
    this.em = em;
    this.qm = new QueryManager(em);
    this.fileId = fileId;
  }

  public void persistLocalVarDeclaration(
    VariableDeclaration node)
  {
    JavaVariable javaVariable = new JavaVariable();
    IVariableBinding nodeBinding = node.resolveBinding();
    SimpleName simpleName = node.getName();
    IMethodBinding methodDeclBinding = nodeBinding.getDeclaringMethod();
    ITypeBinding classBinding = methodDeclBinding.getDeclaringClass();
    String methodName = methodDeclBinding.getName();
    String methodType = methodDeclBinding.getReturnType().getQualifiedName();
    String parametersStr =
      getParameterTypeNamesStr(methodDeclBinding.getParameterTypes());
    String qualifiedName = simpleName.getFullyQualifiedName();
    String qualifiedType = nodeBinding.getType().getQualifiedName();
    String declaringClassName = classBinding.getQualifiedName();
    String methodEntityHashStr = getMethodHashStr(
      declaringClassName, methodType, methodName, parametersStr);
    String entityHashStr = getVariableHashStr(
      methodEntityHashStr, qualifiedType, qualifiedName);
    int modifiers = nodeBinding.getModifiers();
    int methodEntityHash = methodEntityHashStr.hashCode();
    int entityHash = entityHashStr.hashCode();
    int typeHash = qualifiedType.hashCode();

    JavaAstNode javaAstNode = persistJavaAstNodeRow(
      node, SymbolType.VARIABLE,
      node.getInitializer() == null ? AstType.DECLARATION : AstType.DEFINITION,
      entityHash
    );

    JavaAstNode javaAstNodeDef =
      qm.queryParentAstNode(javaAstNode, methodEntityHash);

    setJavaTypedEntityFields(javaVariable, modifiers, typeHash, qualifiedType);

    if (methodDeclBinding.isConstructor()) {
      JavaConstructor javaConstructor =
        qm.queryJavaConstructor(javaAstNodeDef.getId());

      javaConstructor.addJavaConVarLocal(javaVariable);
    } else {
      JavaMethod javaMethod = qm.queryJavaMethod(javaAstNodeDef.getId());

      javaMethod.addJavaMetVarLocal(javaVariable);
    }

    setJavaEntityFields(
      javaVariable, javaAstNode.getId(), entityHash,
      simpleName.toString(), qualifiedName
    );

    persistRow(javaVariable);
  }

  public void persistConstructorUsage(
    ASTNode node, IMethodBinding constructorBinding)
  {
    JavaConstructor javaConstructor = new JavaConstructor();
    ITypeBinding classBinding = constructorBinding.getDeclaringClass();
    String name = constructorBinding.getName();
    String qualifiedName = classBinding.getQualifiedName();
    String methodQualifiedType =
      constructorBinding.getReturnType().getQualifiedName();
    String parametersStr =
      getParameterTypeNamesStr(constructorBinding.getParameterTypes());
    String entityHashStr = getMethodHashStr(
      qualifiedName, methodQualifiedType, name, parametersStr);
    int modifiers = constructorBinding.getModifiers();
    int classHash = qualifiedName.hashCode();
    int entityHash = entityHashStr.hashCode();

    JavaAstNode javaAstNode = persistJavaAstNodeRow(
      node, SymbolType.CONSTRUCTOR, AstType.USAGE, entityHash);

    persistJavaMemberType(
      classHash, classHash, MemberTypeKind.CONSTRUCTOR, modifiers, javaAstNode);

    setJavaEntityFields(
      javaConstructor, javaAstNode.getId(),
      entityHash, name, qualifiedName
    );

    persistRow(javaConstructor);
  }

  public void persistEnumDeclaration(EnumDeclaration node) {
    JavaEnum javaEnum = new JavaEnum();
    List<?> superInterfaceTypes = node.superInterfaceTypes();
    List<?> enumConstants = node.enumConstants();
    SimpleName simpleName = node.getName();
    String declaringClassName = node.resolveBinding().getQualifiedName();
    int modifiers = node.getModifiers();
    int entityHash = declaringClassName.hashCode();

    // Persist Enum constants
    for (int i = 0; i < enumConstants.size(); i++) {
      persistEnumConstantDeclaration(
        javaEnum, (EnumConstantDeclaration) enumConstants.get(i), i);
    }

    JavaAstNode javaAstNode = persistJavaAstNodeRow(
      node, SymbolType.ENUM, AstType.DEFINITION, entityHash);

    persistJavaMemberType(
      entityHash, entityHash, MemberTypeKind.ENUM, modifiers, javaAstNode);

    setJavaEntityFields(
      javaEnum, javaAstNode.getId(), entityHash,
      simpleName.toString(), declaringClassName
    );

    persistInterfaceImplementations(superInterfaceTypes, entityHash);

    persistRow(javaEnum);
  }

  public void persistEnumConstantDeclaration(
    JavaEnum javaEnum, EnumConstantDeclaration node, int index)
  {
    JavaEnumConstant enumConstant = new JavaEnumConstant();
    ITypeBinding declaringClass =
      node.resolveConstructorBinding().getDeclaringClass();
    SimpleName simpleName = node.getName();
    String qualifiedName = simpleName.getFullyQualifiedName();
    String declaringClassName = declaringClass.getQualifiedName();
    String entityHashStr = getEnumConstantHashStr(
      declaringClassName, qualifiedName);
    int modifiers = declaringClass.getModifiers();
    int entityHash = entityHashStr.hashCode();

    enumConstant.setValue(index);
    javaEnum.addJavaEnumConstant(enumConstant);

    JavaAstNode javaAstNode = persistJavaAstNodeRow(
      node, SymbolType.ENUM_CONSTANT,
      AstType.DEFINITION, entityHash);

    // Set JavaMemberType fields
    persistJavaMemberType(
      entityHash, entityHash,
      MemberTypeKind.ENUM_CONSTANT, modifiers, javaAstNode
    );

    setJavaEntityFields(
      enumConstant, javaAstNode.getId(),
      entityHash, simpleName.toString(), qualifiedName
    );
  }

  public void persistMethodUsage(
    Expression node, IMethodBinding methodBinding, SimpleName simpleName)
  {
    JavaMethod javaMethod = new JavaMethod();
    ITypeBinding classBinding = methodBinding.getDeclaringClass();
    String qualifiedName = simpleName.getFullyQualifiedName();
    String qualifiedType = methodBinding.getReturnType().getQualifiedName();
    String declaringClassName = classBinding.getQualifiedName();
    String parametersStr =
      getParameterTypeNamesStr(methodBinding.getParameterTypes());
    String entityHashStr = getMethodHashStr(
      declaringClassName, qualifiedType, qualifiedName, parametersStr);
    int modifiers = methodBinding.getModifiers();
    int classHash = declaringClassName.hashCode();
    int entityHash = entityHashStr.hashCode();
    int typeHash = qualifiedType.hashCode();

    setJavaTypedEntityFields(javaMethod, modifiers, typeHash, qualifiedType);

    JavaAstNode javaAstNode = persistJavaAstNodeRow(
      node, SymbolType.METHOD, AstType.USAGE, entityHash);

    // Set JavaMemberType fields
    persistJavaMemberType(
      classHash, typeHash, MemberTypeKind.METHOD, modifiers, javaAstNode);

    setJavaEntityFields(
      javaMethod, javaAstNode.getId(), entityHash,
      simpleName.toString(), qualifiedName
    );

    persistRow(javaMethod);
  }

  public void persistVariableUsage(
    ASTNode node, IVariableBinding variableBinding)
  {
    JavaVariable javaVariable = new JavaVariable();
    ASTNode parent = node.getParent();
    String qualifiedType = variableBinding.getType().getQualifiedName();
    String name = variableBinding.getName();
    String entityHashStr = "";
    String declaringClassName;
    AstType astType;

    if (variableBinding.isField()) {
      ITypeBinding classBinding = variableBinding.getDeclaringClass();
      declaringClassName = classBinding.getQualifiedName();
      entityHashStr = getFieldHashStr(declaringClassName, qualifiedType, name);

    } else {
      IMethodBinding declaringMethodBinding =
        variableBinding.getVariableDeclaration().getDeclaringMethod();
      ITypeBinding classBinding = declaringMethodBinding.getDeclaringClass();
      String methodName = declaringMethodBinding.getName();
      String methodQualifiedType =
        declaringMethodBinding.getReturnType().getQualifiedName();
      String parametersStr =
        getParameterTypeNamesStr(declaringMethodBinding.getParameterTypes());
      declaringClassName = classBinding.getQualifiedName();
      String methodHashStr = getMethodHashStr(
        declaringClassName, methodQualifiedType, methodName, parametersStr);
      entityHashStr = getVariableHashStr(methodHashStr, qualifiedType, name);
    }

    int modifiers = variableBinding.getModifiers();
    int classHash = declaringClassName.hashCode();
    int entityHash = entityHashStr.hashCode();
    int typeHash = qualifiedType.hashCode();

    setJavaTypedEntityFields(javaVariable, modifiers, typeHash, qualifiedType);

    if (parent instanceof Assignment) {
      Assignment assignment = (Assignment) parent;
      Expression leftHandSide = assignment.getLeftHandSide();

      astType =
        node.getStartPosition() == leftHandSide.getStartPosition() ?
          AstType.WRITE :
          AstType.READ;
    } else if (parent instanceof MethodInvocation) {
      MethodInvocation methodInvocation = (MethodInvocation) parent;
      astType =
        node.getStartPosition() == methodInvocation.getStartPosition() ?
          AstType.WRITE :
          AstType.READ;
    } else if (
      parent instanceof PrefixExpression ||
        parent instanceof PostfixExpression)
    {
      astType = AstType.WRITE;
    } else {
      astType = AstType.READ;
    }

    JavaAstNode javaAstNode = persistJavaAstNodeRow(
      node, SymbolType.VARIABLE, astType, entityHash);

    if (variableBinding.isField()) {
      persistJavaMemberType(
        classHash, typeHash, MemberTypeKind.FIELD, modifiers, javaAstNode);
    }

    setJavaEntityFields(
      javaVariable, javaAstNode.getId(), entityHash, name, name);

    persistRow(javaVariable);
  }

  public void persistEnumConstantUsage(
    Name node, IVariableBinding variableBinding)
  {
    JavaEnumConstant javaEnumConstant = new JavaEnumConstant();
    String qualifiedType = variableBinding.getType().getQualifiedName();
    String name = variableBinding.getName();
    String entityHashStr = getEnumConstantHashStr(qualifiedType, name);
    int modifiers = variableBinding.getModifiers();
    int classHash = qualifiedType.hashCode();
    int entityHash = entityHashStr.hashCode();

    javaEnumConstant.setValue(variableBinding.getVariableId());

    JavaAstNode javaAstNode = persistJavaAstNodeRow(
      node, SymbolType.ENUM_CONSTANT, AstType.USAGE, entityHash
    );

    persistJavaMemberType(
      classHash, classHash,
      MemberTypeKind.ENUM_CONSTANT, modifiers, javaAstNode
    );

    setJavaEntityFields(
      javaEnumConstant, javaAstNode.getId(), entityHash, name, name);

    persistRow(javaEnumConstant);
  }

  public void persistFieldDeclaration(FieldDeclaration node) {
    String declaringClassName = "";
    ASTNode parent = node.getParent();

    if (parent instanceof TypeDeclaration) {
      declaringClassName =
        ((TypeDeclaration) parent).resolveBinding().getQualifiedName();
    } else if (parent instanceof EnumDeclaration) {
      declaringClassName =
        ((EnumDeclaration) parent).resolveBinding().getQualifiedName();
    }

    String qualifiedType = node.getType().resolveBinding().getQualifiedName();

    for (Object varDeclFragObj : node.fragments()) {
      persistFieldDeclarationFragment(
        (VariableDeclarationFragment) varDeclFragObj,
        declaringClassName, qualifiedType);
    }
  }

  private void persistFieldDeclarationFragment(
    VariableDeclarationFragment node,
    String declaringClassName, String qualifiedType)
  {
    JavaVariable javaVariable = new JavaVariable();
    IVariableBinding nodeBinding = node.resolveBinding();
    SimpleName simpleName = node.getName();
    String qualifiedName = simpleName.getFullyQualifiedName();
    String entityHashStr = getFieldHashStr(
      declaringClassName, qualifiedType, qualifiedName);
    int modifiers = nodeBinding.getModifiers();
    int entityHash = entityHashStr.hashCode();
    int classHash = declaringClassName.hashCode();
    int typeHash = qualifiedType.hashCode();

    setJavaTypedEntityFields(javaVariable, modifiers, typeHash, qualifiedType);

    JavaAstNode javaAstNode = persistJavaAstNodeRow(
      node, SymbolType.VARIABLE,
      node.getInitializer() == null ? AstType.DECLARATION : AstType.DEFINITION,
      entityHash
    );

    persistJavaMemberType(
      classHash, typeHash, MemberTypeKind.FIELD, modifiers, javaAstNode);

    setJavaEntityFields(
      javaVariable, javaAstNode.getId(),
      entityHash, simpleName.toString(), qualifiedName
    );

    persistRow(javaVariable);
  }

  public void persistLambdaExpression(LambdaExpression node) {
    JavaMethod javaMethod = new JavaMethod();
    IMethodBinding methodBinding = node.resolveMethodBinding();
    ITypeBinding classBinding = methodBinding.getDeclaringClass();
    String declaringClassName = classBinding.getQualifiedName();
    String qualifiedName = methodBinding.getName();
    String qualifiedType = methodBinding.getReturnType().getQualifiedName();
    String parametersStr =
      getParameterTypeNamesStr(methodBinding.getParameterTypes());
    String entityHashStr = getMethodHashStr(
      declaringClassName, qualifiedType, qualifiedName, parametersStr);
    int modifiers = methodBinding.getModifiers();
    int entityHash = entityHashStr.hashCode();
    int classHash = declaringClassName.hashCode();
    int typeHash = qualifiedType.hashCode();

    setJavaTypedEntityFields(javaMethod, modifiers, typeHash, qualifiedType);

    // persist method's parameters
    for (Object varDeclObj : node.parameters()) {
      persistParameterDeclaration(
        (VariableDeclarationFragment) varDeclObj,
        entityHashStr, javaMethod::addJavaMetVarParam
      );
    }

    JavaAstNode javaAstNode = persistJavaAstNodeRow(
      node, SymbolType.METHOD, AstType.DEFINITION, entityHash);

    persistJavaMemberType(
      classHash, typeHash, MemberTypeKind.METHOD, modifiers, javaAstNode);

    setJavaEntityFields(javaMethod, javaAstNode.getId(), entityHash, "", "");

    persistRow(javaMethod);
  }

  public void persistConstructorDeclaration(MethodDeclaration node) {
    JavaConstructor javaConstructor = new JavaConstructor();
    IMethodBinding methodBinding = node.resolveBinding();
    ITypeBinding classBinding = methodBinding.getDeclaringClass();
    String name = node.getName().toString();
    String methodQualifiedType =
      methodBinding.getReturnType().getQualifiedName();
    String declaringClassName = classBinding.getQualifiedName();
    String parametersStr =
      getParameterTypeNamesStr(methodBinding.getParameterTypes());
    String entityHashStr = getMethodHashStr(
      declaringClassName, methodQualifiedType, name, parametersStr);
    int modifiers = node.getModifiers();
    int entityHash = entityHashStr.hashCode();
    int classHash = declaringClassName.hashCode();

    // Persist constructor's parameters
    for (Object varDeclObj : node.parameters()) {
      persistParameterDeclaration(
        (SingleVariableDeclaration) varDeclObj,
        entityHashStr, javaConstructor::addJavaConVarParam
      );
    }

    JavaAstNode javaAstNode = persistJavaAstNodeRow(
      node, SymbolType.CONSTRUCTOR, AstType.DEFINITION, entityHash);

    persistJavaMemberType(
      classHash, classHash, MemberTypeKind.CONSTRUCTOR, modifiers, javaAstNode);

    setJavaEntityFields(
      javaConstructor, javaAstNode.getId(), entityHash,
      name, declaringClassName
    );

    persistRow(javaConstructor);
  }

  public void persistMethodDeclaration(MethodDeclaration node) {
    JavaMethod javaMethod = new JavaMethod();
    IMethodBinding methodBinding = node.resolveBinding();
    ITypeBinding classBinding = methodBinding.getDeclaringClass();
    String declaringClassName = classBinding.getQualifiedName();
    String parametersStr =
      getParameterTypeNamesStr(methodBinding.getParameterTypes());
    SimpleName simpleName = node.getName();
    String name = simpleName.toString();
    String qualifiedName = simpleName.getFullyQualifiedName();
    String qualifiedType =
      node.getReturnType2().resolveBinding().getQualifiedName();
    AstType astType =
      node.getBody() == null ? AstType.DECLARATION : AstType.DEFINITION;
    String entityHashStr = getMethodHashStr(
      declaringClassName, qualifiedType, qualifiedName, parametersStr);
    int modifiers = node.getModifiers();
    int entityHash = entityHashStr.hashCode();
    int classHash = declaringClassName.hashCode();
    int typeHash = qualifiedType.hashCode();

    setJavaTypedEntityFields(javaMethod, modifiers, typeHash, qualifiedType);

    // persist method's parameters
    for (Object varDeclObj : node.parameters()) {
      persistParameterDeclaration(
        (SingleVariableDeclaration) varDeclObj,
        entityHashStr, javaMethod::addJavaMetVarParam
      );
    }

    JavaAstNode javaAstNode = persistJavaAstNodeRow(
      node, SymbolType.METHOD, astType, entityHash);

    JavaMemberType javaMemberType = persistJavaMemberType(
      classHash, typeHash, MemberTypeKind.METHOD, modifiers, javaAstNode);

    persistMethodRelations(
      classBinding.getSuperclass(), classBinding.getInterfaces(),
      methodBinding, name, javaMemberType.getVisibility(), entityHash
    );

    setJavaEntityFields(
      javaMethod, javaAstNode.getId(), entityHash, name, qualifiedName);

    persistRow(javaMethod);
  }

  public void persistTypeDeclaration(TypeDeclaration node) {
    JavaRecord javaRecord = new JavaRecord();
    Type superclassType = node.getSuperclassType();
    List<?> superInterfaceTypes = node.superInterfaceTypes();
    SimpleName simpleName = node.getName();
    String qualifiedName = node.resolveBinding().getQualifiedName();
    int modifiers = node.getModifiers();
    int entityHash = qualifiedName.hashCode();

    setJavaRecordFields(javaRecord, modifiers);

    JavaAstNode javaAstNode =
      persistJavaAstNodeRow(
        node, SymbolType.TYPE, AstType.DEFINITION, entityHash);

    persistJavaMemberType(
      entityHash, entityHash, MemberTypeKind.TYPE, modifiers, javaAstNode);

    setJavaEntityFields(
      javaRecord, javaAstNode.getId(), entityHash,
      simpleName.toString(), qualifiedName
    );

    persistClassExtensions(superclassType, entityHash);
    persistInterfaceImplementations(superInterfaceTypes, entityHash);

    persistRow(javaRecord);
  }

  public void persistTypeUsage(SimpleType node) {
    JavaRecord javaRecord = new JavaRecord();
    ITypeBinding typeBinding = node.resolveBinding();
    Name name = node.getName();
    String qualifiedName = typeBinding.getQualifiedName();
    boolean isEnum = typeBinding.isEnum();
    int modifiers = typeBinding.getModifiers();
    int entityHash = qualifiedName.hashCode();

    setJavaRecordFields(javaRecord, modifiers);

    JavaAstNode javaAstNode =
      persistJavaAstNodeRow(
        node, isEnum ? SymbolType.ENUM : SymbolType.TYPE,
        AstType.USAGE, entityHash
      );

    persistJavaMemberType(
      entityHash, entityHash,
      isEnum ? MemberTypeKind.ENUM : MemberTypeKind.TYPE,
      modifiers, javaAstNode
    );

    setJavaEntityFields(
      javaRecord, javaAstNode.getId(),
      entityHash, name.toString(), qualifiedName
    );

    persistRow(javaRecord);
  }

  public void persistParameterDeclaration(
    VariableDeclaration node, String entityHashStrPrefix,
    Consumer<JavaVariable> connectParent)
  {
    JavaVariable javaVariable = new JavaVariable();
    IVariableBinding nodeBinding = node.resolveBinding();
    SimpleName simpleName = node.getName();
    String qualifiedType = nodeBinding.getType().getQualifiedName();
    String qualifiedName = simpleName.getFullyQualifiedName();
    String entityHashStr = getVariableHashStr(
      entityHashStrPrefix, qualifiedType, qualifiedName);
    int modifiers = nodeBinding.getModifiers();
    int entityHash = entityHashStr.hashCode();
    int typeHash = qualifiedType.hashCode();

    setJavaTypedEntityFields(javaVariable, modifiers, typeHash, qualifiedType);

    JavaAstNode javaAstNode = persistJavaAstNodeRow(
      node, SymbolType.VARIABLE, AstType.DECLARATION, entityHash);

    setJavaEntityFields(
      javaVariable, javaAstNode.getId(),
      entityHash, simpleName.toString(), qualifiedName
    );

    connectParent.accept(javaVariable);
  }

  public void persistClassExtensions(
    Type superclassType, int entityHash)
  {
    if (superclassType != null) {
      String qualifiedSuperClassName =
        superclassType.resolveBinding().getQualifiedName();
      int superClassHash = qualifiedSuperClassName.hashCode();

      persistJavaInheritance(superClassHash, entityHash);
    }
  }

  public void persistInterfaceImplementations(
    List<?> superInterfaceTypes, int entityHash)
  {
    superInterfaceTypes.forEach(i -> {
      Type aInterface = (Type) i;
      String qualifiedSuperInterfaceName =
        aInterface.resolveBinding().getQualifiedName();
      int superInterfaceHash = qualifiedSuperInterfaceName.hashCode();

      persistJavaInheritance(superInterfaceHash, entityHash);
    });
  }

  public JavaInheritance persistJavaInheritance(
    int baseEntityHash, int derivedEntityHash)
  {
    JavaInheritance javaInheritance = new JavaInheritance();

    setJavaInheritanceFields(
      javaInheritance, baseEntityHash, derivedEntityHash);

    persistRow(javaInheritance);

    return javaInheritance;
  }

  public void persistMethodRelations(
    ITypeBinding superclassBinding, ITypeBinding[] superInterfaceBindings,
    IMethodBinding methodBinding, String methodName,
    Visibility methodVisibility, int methodEntityHash)
  {
    RelationCollector relationCollector =
      new RelationCollector(
        methodBinding, methodName,
        methodVisibility, methodEntityHash
      );

    List<JavaRelation> javaRelations =
      relationCollector.collectBaseMethods(
        superclassBinding, superInterfaceBindings);

    javaRelations.forEach(this::persistRow);
  }

  public JavaMemberType persistJavaMemberType(
    int typeHash, int memberTypeHash, MemberTypeKind memberTypeKind,
    int modifiers, JavaAstNode javaAstNode)
  {
    JavaMemberType javaMemberType = new JavaMemberType();

    setJavaMemberTypeFields(
      javaMemberType, typeHash, memberTypeHash,
      memberTypeKind, modifiers, javaAstNode
    );

    persistRow(javaMemberType);

    return javaMemberType;
  }

  public void persistJavaDoc(Javadoc node, int entityHash) {
    JavaDocComment javaDocComment = new JavaDocComment();
    String commentString = node.toString();

    setJavaDocCommentFields(javaDocComment, commentString, entityHash);

    persistRow(javaDocComment);
  }

  public <T extends ASTNode> JavaAstNode persistJavaAstNodeRow(
    T node, SymbolType symbolType, AstType astType, int entityHash)
  {
    JavaAstNode javaAstNode = new JavaAstNode();
    PositionInfo positionInfo;
    String astValue;

    // visibleinsourcecode: akkor lesz false, ha az adott függvényt, vagy akármit
    // nem közvetlenül hívjuk a forráskódból, hanem hívunk valamit egy libraryből, ami meghívja aztán ezt
    try {
      Method getJavadocMethod =
        node.getClass().getMethod("getJavadoc", (Class<?>[]) null);
      Javadoc javadoc =
        (Javadoc) getJavadocMethod.invoke(node, (Object[]) null);

      persistJavaDoc(javadoc, entityHash);

      positionInfo = new PositionInfo(this.cu, node, javadoc);
      int javadocLen = javadoc.toString().length();
      astValue = node.toString().substring(javadocLen);

    } catch (NoSuchMethodException | NullPointerException e) {
      positionInfo = new PositionInfo(this.cu, node);
      astValue = node.toString();

    } catch (IllegalAccessException | InvocationTargetException e) {
      throw new RuntimeException();
    }

    setJavaAstNodeFields(
      javaAstNode, astValue, positionInfo, fileId,
      entityHash, symbolType, astType, true
    );

    persistRow(javaAstNode);

    return javaAstNode;
  }

  public void persistRow(Object jpaObject) {
    em.getTransaction().begin();
    em.persist(jpaObject);
    em.getTransaction().commit();
  }
}
