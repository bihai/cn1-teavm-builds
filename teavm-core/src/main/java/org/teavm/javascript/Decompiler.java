/*
 *  Copyright 2011 Alexey Andreev.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.teavm.javascript;

import java.util.*;
import org.teavm.common.*;
import org.teavm.javascript.ast.*;
import org.teavm.javascript.spi.GeneratedBy;
import org.teavm.javascript.spi.Generator;
import org.teavm.javascript.spi.InjectedBy;
import org.teavm.model.*;
import org.teavm.model.util.AsyncProgramSplitter;
import org.teavm.model.util.ProgramUtils;

/**
 *
 * @author Alexey Andreev
 */
public class Decompiler {
    private ClassHolderSource classSource;
    private ClassLoader classLoader;
    private Graph graph;
    private LoopGraph loopGraph;
    private GraphIndexer indexer;
    private int[] loops;
    private int[] loopSuccessors;
    private Block[] blockMap;
    private int lastBlockId;
    private RangeTree codeTree;
    private RangeTree.Node currentNode;
    private RangeTree.Node parentNode;
    private Map<MethodReference, Generator> generators = new HashMap<>();
    private Set<MethodReference> methodsToPass = new HashSet<>();
    private MethodNodeCache regularMethodCache;
    private Set<MethodReference> asyncMethods;
    private Set<MethodReference> splitMethods = new HashSet<>();

    public Decompiler(ClassHolderSource classSource, ClassLoader classLoader, Set<MethodReference> asyncMethods,
            Set<MethodReference> asyncFamilyMethods) {
        this.classSource = classSource;
        this.classLoader = classLoader;
        this.asyncMethods = asyncMethods;
        splitMethods.addAll(asyncMethods);
        splitMethods.addAll(asyncFamilyMethods);
    }

    public MethodNodeCache getRegularMethodCache() {
        return regularMethodCache;
    }

    public void setRegularMethodCache(MethodNodeCache regularMethodCache) {
        this.regularMethodCache = regularMethodCache;
    }

    public int getGraphSize() {
        return this.graph.size();
    }

    static class Block {
        public final IdentifiedStatement statement;
        public final List<Statement> body;
        public final int end;
        public final int start;

        public Block(IdentifiedStatement statement, List<Statement> body, int start, int end) {
            this.statement = statement;
            this.body = body;
            this.start = start;
            this.end = end;
        }
    }

    public List<ClassNode> decompile(Collection<String> classNames) {
        List<String> sequence = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        for (String className : classNames) {
            orderClasses(className, visited, sequence);
        }
        final List<ClassNode> result = new ArrayList<>();
        for (int i = 0; i < sequence.size(); ++i) {
            final String className = sequence.get(i);
            result.add(decompile(classSource.get(className)));
        }
        return result;
    }

    public List<String> getClassOrdering(Collection<String> classNames) {
        List<String> sequence = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        for (String className : classNames) {
            orderClasses(className, visited, sequence);
        }
        return sequence;
    }

    public void addGenerator(MethodReference method, Generator generator) {
        generators.put(method, generator);
    }

    public void addMethodToPass(MethodReference method) {
        methodsToPass.add(method);
    }

    private void orderClasses(String className, Set<String> visited, List<String> order) {
        if (!visited.add(className)) {
            return;
        }
        ClassHolder cls = classSource.get(className);
        if (cls == null) {
            return;
        }
        if (cls.getParent() != null) {
            orderClasses(cls.getParent(), visited, order);
        }
        for (String iface : cls.getInterfaces()) {
            orderClasses(iface, visited, order);
        }
        order.add(className);
    }

    public ClassNode decompile(ClassHolder cls) {
        ClassNode clsNode = new ClassNode(cls.getName(), cls.getParent());
        for (FieldHolder field : cls.getFields()) {
            FieldNode fieldNode = new FieldNode(field.getName(), field.getType());
            fieldNode.getModifiers().addAll(mapModifiers(field.getModifiers()));
            fieldNode.setInitialValue(field.getInitialValue());
            clsNode.getFields().add(fieldNode);
        }
        for (MethodHolder method : cls.getMethods()) {
            if (method.getModifiers().contains(ElementModifier.ABSTRACT)) {
                continue;
            }
            if (method.getAnnotations().get(InjectedBy.class.getName()) != null ||
                    methodsToPass.contains(method.getReference())) {
                continue;
            }
            MethodNode methodNode = decompile(method);
            clsNode.getMethods().add(methodNode);
        }
        clsNode.getInterfaces().addAll(cls.getInterfaces());
        clsNode.getModifiers().addAll(mapModifiers(cls.getModifiers()));
        return clsNode;
    }

    public MethodNode decompile(MethodHolder method) {
        return method.getModifiers().contains(ElementModifier.NATIVE) ? decompileNative(method) :
                !asyncMethods.contains(method.getReference()) ? decompileRegular(method) : decompileAsync(method);
    }

    public NativeMethodNode decompileNative(MethodHolder method) {
        Generator generator = generators.get(method.getReference());
        if (generator == null) {
            AnnotationHolder annotHolder = method.getAnnotations().get(GeneratedBy.class.getName());
            if (annotHolder == null) {
                throw new DecompilationException("Method " + method.getOwnerName() + "." + method.getDescriptor() +
                        " is native, but no " + GeneratedBy.class.getName() + " annotation found");
            }
            ValueType annotValue = annotHolder.getValues().get("value").getJavaClass();
            String generatorClassName = ((ValueType.Object)annotValue).getClassName();
            try {
                Class<?> generatorClass = Class.forName(generatorClassName, true, classLoader);
                generator = (Generator)generatorClass.newInstance();
            } catch (ClassNotFoundException | InstantiationException | IllegalAccessException e) {
                throw new DecompilationException("Error instantiating generator " + generatorClassName +
                        " for native method " + method.getOwnerName() + "." + method.getDescriptor());
            }
        }
        NativeMethodNode methodNode = new NativeMethodNode(new MethodReference(method.getOwnerName(),
                method.getDescriptor()));
        methodNode.getModifiers().addAll(mapModifiers(method.getModifiers()));
        methodNode.setGenerator(generator);
        methodNode.setAsync(asyncMethods.contains(method.getReference()));
        return methodNode;
    }

    public RegularMethodNode decompileRegular(MethodHolder method) {
        if (regularMethodCache == null) {
            return decompileRegularCacheMiss(method);
        }
        RegularMethodNode node = regularMethodCache.get(method.getReference());
        if (node == null) {
            node = decompileRegularCacheMiss(method);
            regularMethodCache.store(method.getReference(), node);
        }
        return node;
    }

    public RegularMethodNode decompileRegularCacheMiss(MethodHolder method) {
        RegularMethodNode methodNode = new RegularMethodNode(method.getReference());
        Program program = method.getProgram();
        int[] targetBlocks = new int[program.basicBlockCount()];
        Arrays.fill(targetBlocks, -1);
        methodNode.setBody(getRegularMethodStatement(program, targetBlocks, false).getStatement());
        for (int i = 0; i < program.variableCount(); ++i) {
            methodNode.getVariables().add(program.variableAt(i).getRegister());
        }
        Optimizer optimizer = new Optimizer();
        optimizer.optimize(methodNode, method.getProgram());
        methodNode.getModifiers().addAll(mapModifiers(method.getModifiers()));
        int paramCount = Math.min(method.getSignature().length, program.variableCount());
        for (int i = 0; i < paramCount; ++i) {
            Variable var = program.variableAt(i);
            methodNode.getParameterDebugNames().add(new HashSet<>(var.getDebugNames()));
        }
        return methodNode;
    }

    public AsyncMethodNode decompileAsync(MethodHolder method) {
        if (regularMethodCache == null) {
            return decompileAsyncCacheMiss(method);
        }
        AsyncMethodNode node = regularMethodCache.getAsync(method.getReference());
        if (node == null || !checkAsyncRelevant(node)) {
            node = decompileAsyncCacheMiss(method);
            regularMethodCache.storeAsync(method.getReference(), node);
        }
        return node;
    }

    private boolean checkAsyncRelevant(AsyncMethodNode node) {
        AsyncCallsFinder asyncCallsFinder = new AsyncCallsFinder();
        for (AsyncMethodPart part : node.getBody()) {
            part.getStatement().acceptVisitor(asyncCallsFinder);
        }
        for (MethodReference asyncCall : asyncCallsFinder.asyncCalls) {
            if (!splitMethods.contains(asyncCall)) {
                return false;
            }
        }
        asyncCallsFinder.allCalls.removeAll(asyncCallsFinder.asyncCalls);
        for (MethodReference asyncCall : asyncCallsFinder.allCalls) {
            if (splitMethods.contains(asyncCall)) {
                return false;
            }
        }
        return true;
    }

    private AsyncMethodNode decompileAsyncCacheMiss(MethodHolder method) {
        AsyncMethodNode node = new AsyncMethodNode(method.getReference());
        AsyncProgramSplitter splitter = new AsyncProgramSplitter(classSource, splitMethods);
        splitter.split(method.getProgram());
        for (int i = 0; i < splitter.size(); ++i) {
            AsyncMethodPart part = getRegularMethodStatement(splitter.getProgram(i), splitter.getBlockSuccessors(i),
                    i > 0);
            node.getBody().add(part);
        }
        Program program = method.getProgram();
        for (int i = 0; i < program.variableCount(); ++i) {
            node.getVariables().add(program.variableAt(i).getRegister());
        }
        Optimizer optimizer = new Optimizer();
        optimizer.optimize(node, splitter);
        node.getModifiers().addAll(mapModifiers(method.getModifiers()));
        int paramCount = Math.min(method.getSignature().length, program.variableCount());
        for (int i = 0; i < paramCount; ++i) {
            Variable var = program.variableAt(i);
            node.getParameterDebugNames().add(new HashSet<>(var.getDebugNames()));
        }
        return node;
    }

    private AsyncMethodPart getRegularMethodStatement(Program program, int[] targetBlocks, boolean async) {
        AsyncMethodPart result = new AsyncMethodPart();
        lastBlockId = 1;
        graph = ProgramUtils.buildControlFlowGraph(program);
        int[] weights = new int[graph.size()];
        for (int i = 0; i < weights.length; ++i) {
            weights[i] = program.basicBlockAt(i).getInstructions().size();
        }
        indexer = new GraphIndexer(graph, weights);
        graph = indexer.getGraph();
        loopGraph = new LoopGraph(this.graph);
        unflatCode();
        blockMap = new Block[program.basicBlockCount() * 2 + 1];
        Deque<Block> stack = new ArrayDeque<>();
        BlockStatement rootStmt = new BlockStatement();
        rootStmt.setId("root");
        stack.push(new Block(rootStmt, rootStmt.getBody(), -1, -1));
        StatementGenerator generator = new StatementGenerator();
        generator.classSource = classSource;
        generator.program = program;
        generator.blockMap = blockMap;
        generator.indexer = indexer;
        parentNode = codeTree.getRoot();
        currentNode = parentNode.getFirstChild();
        generator.async = async;
        for (int i = 0; i < this.graph.size(); ++i) {
            Block block = stack.peek();
            while (block.end == i) {
                Block oldBlock = block;
                stack.pop();
                block = stack.peek();
                if (block.start >= 0) {
                    int mappedStart = indexer.nodeAt(block.start);
                    if (blockMap[mappedStart] == oldBlock) {
                        blockMap[mappedStart] = block;
                    }
                }
            }
            while (parentNode.getEnd() == i) {
                currentNode = parentNode.getNext();
                parentNode = parentNode.getParent();
            }
            for (Block newBlock : createBlocks(i)) {
                block.body.add(newBlock.statement);
                stack.push(newBlock);
                block = newBlock;
            }
            int node = i < indexer.size() ? indexer.nodeAt(i) : -1;
            int next = i + 1;
            int head = loops[i];
            if (head != -1 && loopSuccessors[head] == next) {
                next = head;
            }
            if (node >= 0) {
                generator.currentBlock = program.basicBlockAt(node);
                int tmp = indexer.nodeAt(next);
                generator.nextBlock = tmp >= 0 && next < indexer.size() ? program.basicBlockAt(tmp) : null;
                generator.statements.clear();
                InstructionLocation lastLocation = null;
                NodeLocation nodeLocation = null;
                List<Instruction> instructions = generator.currentBlock.getInstructions();
                for (int j = 0; j < instructions.size(); ++j) {
                    Instruction insn = generator.currentBlock.getInstructions().get(j);
                    if (insn.getLocation() != null && lastLocation != insn.getLocation()) {
                        lastLocation = insn.getLocation();
                        nodeLocation = new NodeLocation(lastLocation.getFileName(), lastLocation.getLine());
                    }
                    if (insn.getLocation() != null) {
                        generator.setCurrentLocation(nodeLocation);
                    }
                    insn.acceptVisitor(generator);
                }
                if (targetBlocks[node] >= 0) {
                    GotoPartStatement stmt = new GotoPartStatement();
                    stmt.setPart(targetBlocks[node]);
                    generator.statements.add(stmt);
                }

                for (TryCatchBlock tryCatch : generator.currentBlock.getTryCatchBlocks()) {
                    TryCatchStatement tryCatchStmt = new TryCatchStatement();
                    tryCatchStmt.setExceptionType(tryCatch.getExceptionType());
                    tryCatchStmt.setExceptionVariable(tryCatch.getExceptionVariable().getIndex());
                    tryCatchStmt.getProtectedBody().addAll(generator.statements);
                    generator.statements.clear();
                    generator.statements.add(tryCatchStmt);
                    Statement handlerStmt = generator.generateJumpStatement(tryCatch.getHandler());
                    if (handlerStmt != null) {
                        tryCatchStmt.getHandler().add(handlerStmt);
                    }
                }
                block.body.addAll(generator.statements);
            }
        }
        SequentialStatement resultBody = new SequentialStatement();
        resultBody.getSequence().addAll(rootStmt.getBody());
        result.setStatement(resultBody);
        return result;
    }

    private Set<NodeModifier> mapModifiers(Set<ElementModifier> modifiers) {
        Set<NodeModifier> result = EnumSet.noneOf(NodeModifier.class);
        if (modifiers.contains(ElementModifier.STATIC)) {
            result.add(NodeModifier.STATIC);
        }
        if (modifiers.contains(ElementModifier.INTERFACE)) {
            result.add(NodeModifier.INTERFACE);
        }
        if (modifiers.contains(ElementModifier.ENUM)) {
            result.add(NodeModifier.ENUM);
        }
        if (modifiers.contains(ElementModifier.SYNCHRONIZED)) {
            result.add(NodeModifier.SYNCHRONIZED);
        }
        return result;
    }

    private List<Block> createBlocks(int start) {
        List<Block> result = new ArrayList<>();
        while (currentNode != null && currentNode.getStart() == start) {
            Block block;
            IdentifiedStatement statement;
            boolean loop = false;
            if (loopSuccessors[start] == currentNode.getEnd() || isSingleBlockLoop(start)) {
                WhileStatement whileStatement = new WhileStatement();
                statement = whileStatement;
                block = new Block(statement, whileStatement.getBody(), start, currentNode.getEnd());
                loop = true;
            } else {
                BlockStatement blockStatement = new BlockStatement();
                statement = blockStatement;
                block = new Block(statement, blockStatement.getBody(), start, currentNode.getEnd());
            }
            result.add(block);
            int mappedIndex = indexer.nodeAt(currentNode.getEnd());
            if (mappedIndex >= 0 && (blockMap[mappedIndex] == null ||
                    !(blockMap[mappedIndex].statement instanceof WhileStatement))) {
                blockMap[mappedIndex] = block;
            }
            if (loop) {
                blockMap[indexer.nodeAt(start)] = block;
            }
            parentNode = currentNode;
            currentNode = currentNode.getFirstChild();
        }
        for (Block block : result) {
            block.statement.setId("block" + lastBlockId++);
        }
        return result;
    }

    private boolean isSingleBlockLoop(int index) {
        for (int succ : graph.outgoingEdges(index)) {
            if (succ == index) {
                return true;
            }
        }
        return false;
    }

    private void unflatCode() {
        Graph graph = this.graph;
        int sz = graph.size();

        // Find where each loop ends
        //
        int[] loopSuccessors = new int[sz];
        Arrays.fill(loopSuccessors, sz + 1);
        for (int node = 0; node < sz; ++node) {
            Loop loop = loopGraph.loopAt(node);
            while (loop != null) {
                loopSuccessors[loop.getHead()] = node + 1;
                loop = loop.getParent();
            }
        }

        // For each node find head of loop this node belongs to.
        //
        int[] loops = new int[sz];
        Arrays.fill(loops, -1);
        for (int head = 0; head < sz; ++head) {
            int end = loopSuccessors[head];
            if (end > sz) {
                continue;
            }
            for (int node = head + 1; node < end; ++node) {
                loops[node] = head;
            }
        }

        List<RangeTree.Range> ranges = new ArrayList<>();
        for (int node = 0; node < sz; ++node) {
            if (loopSuccessors[node] <= sz) {
                ranges.add(new RangeTree.Range(node, loopSuccessors[node]));
            }
            int start = sz;
            for (int prev : graph.incomingEdges(node)) {
                start = Math.min(start, prev);
            }
            if (start < node - 1) {
                ranges.add(new RangeTree.Range(start, node));
            }
        }
        for (int node = 0; node < sz; ++node) {
            if (isSingleBlockLoop(node)) {
                ranges.add(new RangeTree.Range(node, node + 1));
            }
        }
        codeTree = new RangeTree(sz + 1, ranges);
        this.loopSuccessors = loopSuccessors;
        this.loops = loops;
    }
}
