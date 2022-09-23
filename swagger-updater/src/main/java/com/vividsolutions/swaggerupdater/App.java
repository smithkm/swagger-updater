package com.vividsolutions.swaggerupdater;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.stream.Collector;
import java.util.stream.Collectors;

import com.github.javaparser.JavaParser;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.AnnotationMemberDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.Name;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.visitor.ModifierVisitor;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.github.javaparser.utils.SourceRoot;
import com.github.javaparser.utils.SourceRoot.Callback;
import com.github.javaparser.utils.SourceRoot.Callback.Result;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;

/**
 * Hello world!
 *
 */
public class App 
{
    
    public static boolean isOldSwagger(Name name) {
        return name.asString().startsWith("io.swagger.annotations");
    }
    
    public static Optional<Expression> annotationValue(NormalAnnotationExpr expr, final String name){
        return expr.getPairs().stream().filter(pair->pair.getNameAsString().equals(name)).findAny().map(MemberValuePair::getValue);
    }
    
    public static Expression singleton(ArrayInitializerExpr expr) {
        if (expr.getValues().size() !=1) {
            throw new IllegalArgumentException("Expected singleton");
        }
        return expr.getValues().getFirst().get();
    }
    
    public static ArrayInitializerExpr arrayExprMap(ArrayInitializerExpr expr, Function<Expression, Expression> mapper) {
        var collector = Collector.of(
                NodeList<Expression>::new, 
                (BiConsumer<NodeList<Expression>,Expression>) NodeList<Expression>::add, 
                (a,b)->{a.addAll(b);return a;}); 
        var values = expr.getValues().stream().map(mapper).collect(collector);
        var result = new ArrayInitializerExpr(values);
        return result;
    }
    
    public static void find(Path projectDir) throws IOException {
        SourceRoot sourceRoot = new SourceRoot(projectDir);
        sourceRoot.parse("ca.bc.gov.nrs.wfim.api.rest.v1.endpoints", (Callback)(localPath, absolutePath, resultCu)->{
            //System.out.println(localPath.toString());
            return resultCu.getResult().map(cu->{
                if(cu.getImports().stream().anyMatch(importDecl->isOldSwagger(importDecl.getName()))) {
                    System.out.println(localPath.toString()+" needs to be updated");
                    
                    cu.walk(ClassOrInterfaceDeclaration.class, classDecl->{
                        classDecl.getAnnotationByClass(Api.class).ifPresent(Node::removeForced);
                    });
                    cu.walk(MethodDeclaration.class, method->{
                        method.getAnnotationByClass(ApiOperation.class).ifPresent(anno->{
                            final var newAnno = new NormalAnnotationExpr();
                            newAnno.setName(new Name(Operation.class.getSimpleName()));
                            annotationValue(anno.asNormalAnnotationExpr(), "value").ifPresent(value -> newAnno.addPair("summary", value));
                            annotationValue(anno.asNormalAnnotationExpr(), "notes").ifPresent(value -> newAnno.addPair("description", value));
                            annotationValue(anno.asNormalAnnotationExpr(), "authorizations").ifPresent(value -> {
                                var auth = singleton(value.asArrayInitializerExpr()).asNormalAnnotationExpr();
                                var securityRequirements = new NormalAnnotationExpr();
                                securityRequirements.setName(SecurityRequirements.class.getSimpleName());
                                annotationValue(auth, "value").ifPresent(value2 -> securityRequirements.addPair("description", value2));
                                annotationValue(auth, "scopes").ifPresent(value2 -> {
                                    value2.asArrayInitializerExpr().getValues().stream().map(scopeAnno->scopeAnno.asSingleMemberAnnotationExpr().getMemberValue());
                                    securityRequirements.addPair("scopes", arrayExprMap(value2.asArrayInitializerExpr(), 
                                            scopeAnno->((annotationValue(scopeAnno.asNormalAnnotationExpr(), "scope").get()))));
                                    });
                                newAnno.addPair("security", securityRequirements);
                                });
                            anno.replace(newAnno);
                        });
                    });
                    
                    cu.walk(ImportDeclaration.class, importDecl->{
                        if(importDecl.getName().getQualifier().map(qualifier->qualifier.asString().startsWith("io.swagger.annotations")).orElse(false)) {
                            importDecl.removeForced();
                        }
                    });
                    
                    cu.addImport(io.swagger.v3.oas.annotations.Operation.class);
                    cu.addImport(io.swagger.v3.oas.annotations.Parameter.class);
                    cu.addImport(io.swagger.v3.oas.annotations.Parameters.class);
                    cu.addImport(io.swagger.v3.oas.annotations.enums.ParameterIn.class);
                    cu.addImport(io.swagger.v3.oas.annotations.media.Content.class);
                    cu.addImport(io.swagger.v3.oas.annotations.media.Schema.class);
                    cu.addImport(io.swagger.v3.oas.annotations.responses.ApiResponse.class);
                    cu.addImport(io.swagger.v3.oas.annotations.responses.ApiResponses.class);
                    cu.addImport(io.swagger.v3.oas.annotations.security.SecurityRequirement.class);
                    cu.addImport(io.swagger.v3.oas.annotations.extensions.Extension.class);
                    cu.addImport(io.swagger.v3.oas.annotations.extensions.ExtensionProperty.class);
                    cu.addImport(io.swagger.v3.oas.annotations.headers.Header.class);
                    
                    return Result.SAVE;
                } else {
                    System.out.println(localPath.toString()+" does not need to be updated");
                    return Result.DONT_SAVE;
                }
            }).orElse(Result.DONT_SAVE);
        });
    }
    public static void main( String[] args ) throws IOException
    {
        find(Path.of("/home/kevin/git/wfim-incidents-api/wfim-incidents-api-rest-endpoints/src/main/java/"));
    }
}
