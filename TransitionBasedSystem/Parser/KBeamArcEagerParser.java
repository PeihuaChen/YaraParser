/**
 * Copyright 2014, Yahoo! Inc.
 * Licensed under the terms of the Apache License 2.0. See LICENSE file at the project root for terms.
 */

package TransitionBasedSystem.Parser;

import Accessories.CoNLLReader;
import Accessories.Pair;
import Learning.AveragedPerceptron;
import Structures.IndexMaps;
import Structures.Sentence;
import TransitionBasedSystem.Configuration.BeamElement;
import TransitionBasedSystem.Configuration.Configuration;
import TransitionBasedSystem.Configuration.GoldConfiguration;
import TransitionBasedSystem.Configuration.State;
import TransitionBasedSystem.Features.FeatureExtractor;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.TreeSet;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class KBeamArcEagerParser extends TransitionBasedParser {
    /**
     * Any kind of classifier that can give us scores
     */
    AveragedPerceptron classifier;

    ArrayList<Integer> dependencyRelations;

    int featureLength;

    // for pruning irrelevant search space
    HashMap<Integer, HashMap<Integer, HashSet<Integer>>> headDepSet;

    IndexMaps maps;

    ExecutorService executor;
    CompletionService<ArrayList<BeamElement>> pool;


    public KBeamArcEagerParser(AveragedPerceptron classifier, ArrayList<Integer> dependencyRelations,
                               HashMap<Integer, HashMap<Integer, HashSet<Integer>>> headDepSet, int featureLength, IndexMaps maps, int numOfThreads) {
        this.classifier = classifier;
        this.dependencyRelations = dependencyRelations;
        this.featureLength = featureLength;
        this.headDepSet = headDepSet;
        this.maps = maps;
        executor = Executors.newFixedThreadPool(numOfThreads);
        pool = new ExecutorCompletionService<ArrayList<BeamElement>>(executor);
    }

    private void parseWithOneThread( ArrayList<Configuration> beam ,TreeSet<BeamElement> beamPreserver, Sentence sentence, boolean rootFirst, int beamWidth)  throws Exception{
        for (int b = 0; b < beam.size(); b++) {
            Configuration configuration = beam.get(b);
            State currentState = configuration.state;
            float prevScore = configuration.score;
            boolean canShift = ArcEager.canDo(Actions.Shift, currentState);
            boolean canReduce = ArcEager.canDo(Actions.Reduce, currentState);
            boolean canRightArc = ArcEager.canDo(Actions.RightArc, currentState);
            boolean canLeftArc = ArcEager.canDo(Actions.LeftArc, currentState);
            long[] features = FeatureExtractor.extractAllParseFeatures(configuration, featureLength);
            if (!canShift
                    && !canReduce
                    && !canRightArc
                    && !canLeftArc) {
                float addedScore = prevScore;
                beamPreserver.add(new BeamElement(addedScore, b, 4, -1));

                if (beamPreserver.size() > beamWidth)
                    beamPreserver.pollFirst();
            }

            if (canShift) {
                float score = classifier.score(features, 0, true);
                float addedScore = score + prevScore;
                beamPreserver.add(new BeamElement(addedScore, b, 0, -1));

                if (beamPreserver.size() > beamWidth)
                    beamPreserver.pollFirst();
            }

            if (canReduce) {
                float score = classifier.score(features, 1, true);
                float addedScore = score + prevScore;
                beamPreserver.add(new BeamElement(addedScore, b, 1, -1));

                if (beamPreserver.size() > beamWidth)
                    beamPreserver.pollFirst();
            }

            if (canRightArc) {
                int headPos = sentence.posAt(configuration.state.peek());
                int depPos = sentence.posAt(configuration.state.bufferHead());
                for (int dependency : dependencyRelations) {
                    if ((!canLeftArc && !canShift && !canReduce) || (rootFirst && canRightArc) || (headDepSet.containsKey(headPos) && headDepSet.get(headPos).containsKey(depPos)
                            && headDepSet.get(headPos).get(depPos).contains(dependency))) {
                        float score = classifier.score(features, 3 + dependency, true);
                        float addedScore = score + prevScore;
                        beamPreserver.add(new BeamElement(addedScore, b, 2, dependency));

                        if (beamPreserver.size() > beamWidth)
                            beamPreserver.pollFirst();
                    }
                }
            }

            if (canLeftArc) {
                int headPos = sentence.posAt(configuration.state.bufferHead());
                int depPos = sentence.posAt(configuration.state.peek());
                for (int dependency : dependencyRelations) {
                    if ((!canShift && !canRightArc && !canReduce) || (rootFirst && canLeftArc) || (headDepSet.containsKey(headPos) && headDepSet.get(headPos).containsKey(depPos)
                            && headDepSet.get(headPos).get(depPos).contains(dependency))) {
                        float score = classifier.score(features, 3 + dependencyRelations.size() + dependency, true);
                        float addedScore = score + prevScore;
                        beamPreserver.add(new BeamElement(addedScore, b, 3, dependency));

                        if (beamPreserver.size() > beamWidth)
                            beamPreserver.pollFirst();
                    }
                }
            }
        }
    }

    public Configuration parse(Sentence sentence, boolean rootFirst, int beamWidth, int numOfThreads) throws Exception {
        Configuration initialConfiguration = new Configuration(sentence, rootFirst);

        ArrayList<Configuration> beam = new ArrayList<Configuration>(beamWidth);
        beam.add(initialConfiguration);

        while (!ArcEager.isTerminal(beam)) {
            TreeSet<BeamElement> beamPreserver = new TreeSet<BeamElement>();

            if (numOfThreads == 1) {
               parseWithOneThread(beam,beamPreserver,sentence,rootFirst,beamWidth);
            } else {
                for (int b = 0; b < beam.size(); b++) {
                    pool.submit(new BeamScorerThread(true, classifier, beam.get(b),
                            dependencyRelations, featureLength, headDepSet, b, rootFirst));
                }
                for (int b = 0; b < beam.size(); b++) {
                    for (BeamElement element : pool.take().get()) {
                        beamPreserver.add(element);
                        if (beamPreserver.size() > beamWidth)
                            beamPreserver.pollFirst();
                    }
                }
            }


            ArrayList<Configuration> repBeam = new ArrayList<Configuration>(beamWidth);
            for (BeamElement beamElement : beamPreserver.descendingSet()) {
                if (repBeam.size() >= beamWidth)
                    break;
                int b = beamElement.number;
                int action = beamElement.action;
                int label = beamElement.label;
                float score = beamElement.score;

                Configuration newConfig = beam.get(b).clone();

                if (action == 0) {
                    ArcEager.shift(newConfig.state);
                    newConfig.addAction(0);
                } else if (action == 1) {
                    ArcEager.reduce(newConfig.state);
                    newConfig.addAction(1);
                } else if (action == 2) {
                    ArcEager.rightArc(newConfig.state, label);
                    newConfig.addAction(3 + label);
                } else if (action == 3) {
                    ArcEager.leftArc(newConfig.state, label);
                    newConfig.addAction(3 + dependencyRelations.size() + label);
                } else if (action == 4) {
                    ArcEager.unShift(newConfig.state);
                    newConfig.addAction(2);
                }
                newConfig.setScore(score);
                repBeam.add(newConfig);
            }
            beam = repBeam;
        }

        Configuration bestConfiguration = null;
        float bestScore = Float.NEGATIVE_INFINITY;
        for (Configuration configuration : beam) {
            if (configuration.getScore(true) > bestScore) {
                bestScore = configuration.getScore(true);
                bestConfiguration = configuration;
            }
        }
        return bestConfiguration;
    }

    private void parsePartialWithOneThread( ArrayList<Configuration> beam ,TreeSet<BeamElement> beamPreserver, Boolean isNonProjective, GoldConfiguration goldConfiguration, int beamWidth)  throws Exception{
        for (int b = 0; b < beam.size(); b++) {
            Configuration configuration = beam.get(b);
            State currentState = configuration.state;
            float prevScore = configuration.score;
            boolean canShift = ArcEager.canDo(Actions.Shift, currentState);
            boolean canReduce = ArcEager.canDo(Actions.Reduce, currentState);
            boolean canRightArc = ArcEager.canDo(Actions.RightArc, currentState);
            boolean canLeftArc = ArcEager.canDo(Actions.Reduce, currentState);
            long[] features = FeatureExtractor.extractAllParseFeatures(configuration, featureLength);
            if (!canShift
                    && !canReduce
                    && !canRightArc
                    && !canLeftArc) {
                beamPreserver.add(new BeamElement(prevScore, b, 4, -1));

                if (beamPreserver.size() > beamWidth)
                    beamPreserver.pollFirst();
            }

            if (canShift) {
                if (isNonProjective || goldConfiguration.actionCost(Actions.Shift, -1, currentState) == 0) {
                    float score = classifier.score(features, 0, true);
                    float addedScore = score + prevScore;
                    beamPreserver.add(new BeamElement(addedScore, b, 0, -1));

                    if (beamPreserver.size() > beamWidth)
                        beamPreserver.pollFirst();
                }
            }

            if (canReduce) {
                if (isNonProjective || goldConfiguration.actionCost(Actions.Reduce, -1, currentState) == 0) {
                    float score = classifier.score(features, 1, true);
                    float addedScore = score + prevScore;
                    beamPreserver.add(new BeamElement(addedScore, b, 1, -1));

                    if (beamPreserver.size() > beamWidth)
                        beamPreserver.pollFirst();
                }
            }

            if (canRightArc) {
                for (int dependency : dependencyRelations) {
                    if (isNonProjective || goldConfiguration.actionCost(Actions.RightArc, dependency, currentState) == 0) {
                        float score = classifier.score(features, 3 + dependency, true);
                        float addedScore = score + prevScore;
                        beamPreserver.add(new BeamElement(addedScore, b, 2, dependency));

                        if (beamPreserver.size() > beamWidth)
                            beamPreserver.pollFirst();
                    }
                }
            }

            if (canLeftArc) {
                for (int dependency : dependencyRelations) {
                    if (isNonProjective || goldConfiguration.actionCost(Actions.LeftArc, dependency, currentState) == 0) {
                        float score = classifier.score(features, 3 + dependencyRelations.size() + dependency, true);
                        float addedScore = score + prevScore;
                        beamPreserver.add(new BeamElement(addedScore, b, 3, dependency));

                        if (beamPreserver.size() > beamWidth)
                            beamPreserver.pollFirst();
                    }
                }
            }
        }

        //todo
        if (beamPreserver.size() == 0) {
            for (int b = 0; b < beam.size(); b++) {
                Configuration configuration = beam.get(b);
                State currentState = configuration.state;
                float prevScore = configuration.score;
                boolean canShift = ArcEager.canDo(Actions.Shift, currentState);
                boolean canReduce = ArcEager.canDo(Actions.Reduce, currentState);
                boolean canRightArc = ArcEager.canDo(Actions.RightArc, currentState);
                boolean canLeftArc = ArcEager.canDo(Actions.LeftArc, currentState);
                long[] features = FeatureExtractor.extractAllParseFeatures(configuration, featureLength);
                if (!canShift
                        && !canReduce
                        && !canRightArc
                        && !canLeftArc) {
                    beamPreserver.add(new BeamElement(prevScore, b, 4, -1));

                    if (beamPreserver.size() > beamWidth)
                        beamPreserver.pollFirst();
                }

                if (canShift) {
                    float score = classifier.score(features, 0, true);
                    float addedScore = score + prevScore;
                    beamPreserver.add(new BeamElement(addedScore, b, 0, -1));

                    if (beamPreserver.size() > beamWidth)
                        beamPreserver.pollFirst();
                }

                if (canReduce) {
                    float score = classifier.score(features, 1, true);
                    float addedScore = score + prevScore;
                    beamPreserver.add(new BeamElement(addedScore, b, 1, -1));

                    if (beamPreserver.size() > beamWidth)
                        beamPreserver.pollFirst();
                }

                if (canRightArc) {
                    for (int dependency : dependencyRelations) {
                        float score = classifier.score(features, 3 + dependency, true);
                        float addedScore = score + prevScore;
                        beamPreserver.add(new BeamElement(addedScore, b, 2, dependency));

                        if (beamPreserver.size() > beamWidth)
                            beamPreserver.pollFirst();
                    }
                }

                if (canLeftArc) {
                    for (int dependency : dependencyRelations) {
                        float score = classifier.score(features, 3 + dependencyRelations.size() + dependency, true);
                        float addedScore = score + prevScore;
                        beamPreserver.add(new BeamElement(addedScore, b, 3, dependency));

                        if (beamPreserver.size() > beamWidth)
                            beamPreserver.pollFirst();
                    }
                }
            }
        }
    }

    public Configuration parsePartial(GoldConfiguration goldConfiguration, Sentence sentence, boolean rootFirst, int beamWidth, int numOfThreads) throws Exception {
        Configuration initialConfiguration = new Configuration(sentence, rootFirst);
        boolean isNonProjective = false;
        if (goldConfiguration.isNonprojective()) {
            isNonProjective = true;
        }

        ArrayList<Configuration> beam = new ArrayList<Configuration>(beamWidth);
        beam.add(initialConfiguration);

        while (!ArcEager.isTerminal(beam)) {
            TreeSet<BeamElement> beamPreserver = new TreeSet<BeamElement>();

            if (numOfThreads == 1) {
               parsePartialWithOneThread(beam,beamPreserver,isNonProjective,goldConfiguration,beamWidth);
            } else {
                for (int b = 0; b < beam.size(); b++) {
                    pool.submit(new PartialTreeBeamScorerThread(true, classifier, goldConfiguration, beam.get(b),
                            dependencyRelations, featureLength, headDepSet, b));
                }
                for (int b = 0; b < beam.size(); b++) {
                    for (BeamElement element : pool.take().get()) {
                        beamPreserver.add(element);
                        if (beamPreserver.size() > beamWidth)
                            beamPreserver.pollFirst();
                    }
                }
            }

            ArrayList<Configuration> repBeam = new ArrayList<Configuration>(beamWidth);
            for (BeamElement beamElement : beamPreserver.descendingSet()) {
                if (repBeam.size() >= beamWidth)
                    break;
                int b = beamElement.number;
                int action = beamElement.action;
                int label = beamElement.label;
                float score = beamElement.score;

                Configuration newConfig = beam.get(b).clone();

                if (action == 0) {
                    ArcEager.shift(newConfig.state);
                    newConfig.addAction(0);
                } else if (action == 1) {
                    ArcEager.reduce(newConfig.state);
                    newConfig.addAction(1);
                } else if (action == 2) {
                    ArcEager.rightArc(newConfig.state, label);
                    newConfig.addAction(3 + label);
                } else if (action == 3) {
                    ArcEager.leftArc(newConfig.state, label);
                    newConfig.addAction(3 + dependencyRelations.size() + label);
                } else if (action == 4) {
                    ArcEager.unShift(newConfig.state);
                    newConfig.addAction(2);
                }
                newConfig.setScore(score);
                repBeam.add(newConfig);
            }
            beam = repBeam;
        }

        Configuration bestConfiguration = null;
        float bestScore = Float.NEGATIVE_INFINITY;
        for (Configuration configuration : beam) {
            if (configuration.getScore(true) > bestScore) {
                bestScore = configuration.getScore(true);
                bestConfiguration = configuration;
            }
        }
        return bestConfiguration;
    }

    public void parseConllFile(String inputFile, String outputFile, boolean rootFirst, int beamWidth, boolean labeled, boolean lowerCased, int numThreads, boolean partial) throws Exception {
        if (partial || numThreads == 1)
            parseConllFileNoParallel(inputFile, outputFile, rootFirst, beamWidth, labeled, lowerCased, numThreads, partial);
        else
            parseConllFileParallel(inputFile, outputFile, rootFirst, beamWidth, lowerCased, numThreads);
    }

    /**
     * Needs Conll 2006 format
     *
     * @param inputFile
     * @param outputFile
     * @param rootFirst
     * @param beamWidth
     * @throws Exception
     */
    public void parseConllFileNoParallel(String inputFile, String outputFile, boolean rootFirst, int beamWidth, boolean labeled, boolean lowerCased, int numOfThreads, boolean partial) throws Exception {
        CoNLLReader reader = new CoNLLReader(inputFile);

        long start = System.currentTimeMillis();
        int allArcs = 0;
        int size = 0;
        BufferedWriter writer = new BufferedWriter(new FileWriter(outputFile + ".tmp"));
        int dataCount = 0;

        while (true) {
            ArrayList<GoldConfiguration> data = reader.readData(15000, true, labeled, rootFirst, lowerCased, maps);
            size += data.size();
            if (data.size() == 0)
                break;

            for (GoldConfiguration goldConfiguration : data) {
                dataCount++;
                if (dataCount % 100 == 0)
                    System.err.print(dataCount + " ... ");
                Configuration bestParse;
                if (partial)
                    bestParse = parsePartial(goldConfiguration, goldConfiguration.getSentence(), rootFirst, beamWidth, numOfThreads);
                else bestParse = parse(goldConfiguration.getSentence(), rootFirst, beamWidth, numOfThreads);

                int[] words = goldConfiguration.getSentence().getWords();
                allArcs += words.length - 1;

                StringBuilder finalOutput = new StringBuilder();
                for (int i = 0; i < words.length; i++) {
                    int w = i + 1;
                    int head = bestParse.state.getHead(w);
                    int dep = bestParse.state.getDependency(w);

                    if (w == bestParse.state.rootIndex && !rootFirst)
                        continue;

                    if (head == bestParse.state.rootIndex)
                        head = 0;

                    String label=head==0 ? maps.rootString : maps.revWords[dep];
                    String output = head + "\t" +label  + "\n";
                    finalOutput.append(output);
                }
                finalOutput.append("\n");
                writer.write(finalOutput.toString());

            }
        }

        System.err.print("\n");
        long end = System.currentTimeMillis();
        float each = (1.0f * (end - start)) / size;
        float eacharc = (1.0f * (end - start)) / allArcs;

        writer.flush();
        writer.close();

        DecimalFormat format = new DecimalFormat("##.00");

        System.err.print(format.format(eacharc) + " ms for each arc!\n");
        System.err.print(format.format(each) + " ms for each sentence!\n\n");

        BufferedReader gReader = new BufferedReader(new FileReader(inputFile));
        BufferedReader pReader = new BufferedReader(new FileReader(outputFile + ".tmp"));
        BufferedWriter pwriter = new BufferedWriter(new FileWriter(outputFile));

        String line;

        while ((line = pReader.readLine()) != null) {
            String gLine = gReader.readLine();
            if (line.trim().length() > 0) {
                while (gLine.trim().length() == 0)
                    gLine = gReader.readLine();
                String[] ps = line.split("\t");
                String[] gs = gLine.split("\t");
                gs[6] = ps[0];
                gs[7] = ps[1];
                StringBuilder output = new StringBuilder();
                for (int i = 0; i < gs.length; i++) {
                    output.append(gs[i]).append("\t");
                }
                pwriter.write(output.toString().trim() + "\n");
            } else {
                pwriter.write("\n");
            }
        }
        pwriter.flush();
        pwriter.close();
    }

    public void parseTaggedFile(String inputFile, String outputFile, boolean rootFirst, int beamWidth, boolean lowerCased, String separator, int numOfThreads) throws Exception {
        BufferedReader reader = new BufferedReader(new FileReader(inputFile));
        BufferedWriter writer = new BufferedWriter(new FileWriter(outputFile));
        long start = System.currentTimeMillis();

        ExecutorService executor = Executors.newFixedThreadPool(numOfThreads);
        CompletionService<Pair<String,Integer>> pool = new ExecutorCompletionService<Pair<String,Integer>>(executor);


        String line;
        int count = 0;
        int lineNum=0;
        while ((line = reader.readLine()) != null) {
            pool.submit(new ParseTaggedThread(lineNum++,line,separator,rootFirst,lowerCased,maps,beamWidth,this));

            if(lineNum%1000==0){
                String[] outs=new String[lineNum];
                for(int i=0;i<lineNum;i++){
                    count++;
                    if (count % 100 == 0)
                        System.err.print(count + "...");
                    Pair<String,Integer> result=pool.take().get();
                    outs[result.second]=result.first;
                }

                for (int i=0;i<lineNum;i++){
                    if(outs[i].length()>0){
                        writer.write(outs[i]);
                    }
                }

                lineNum=0;
            }
        }

        if(lineNum>0){
            String[] outs=new String[lineNum];
            for(int i=0;i<lineNum;i++){
                count++;
                if (count % 100 == 0)
                    System.err.print(count + "...");
                Pair<String,Integer> result=pool.take().get();
                outs[result.second]=result.first;
            }

            for (int i=0;i<lineNum;i++){

                if(outs[i].length()>0){
                    writer.write(outs[i]);
                }
            }
        }

        long end = System.currentTimeMillis();
        System.out.println("\n" + (end - start) + " ms");
        writer.flush();
        writer.close();
        System.out.println("done!");
    }

    public void parseConllFileParallel(String inputFile, String outputFile, boolean rootFirst, int beamWidth, boolean lowerCased, int numThreads) throws Exception {
        CoNLLReader reader = new CoNLLReader(inputFile);

        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CompletionService<Pair<Configuration, Integer>> pool = new ExecutorCompletionService<Pair<Configuration, Integer>>(executor);

        long start = System.currentTimeMillis();
        int allArcs = 0;
        int size = 0;
        BufferedWriter writer = new BufferedWriter(new FileWriter(outputFile + ".tmp"));
        int dataCount = 0;

        while (true) {
            ArrayList<GoldConfiguration> data = reader.readData(15000, true, true, rootFirst, lowerCased, maps);
            size += data.size();
            if (data.size() == 0)
                break;

            int index = 0;
            Configuration[] confs = new Configuration[data.size()];

            for (GoldConfiguration goldConfiguration : data) {
                ParseThread thread = new ParseThread(index, classifier, dependencyRelations, featureLength, headDepSet, goldConfiguration.getSentence(), rootFirst, beamWidth);
                pool.submit(thread);
                index++;
            }

            for (int i = 0; i < confs.length; i++) {
                dataCount++;
                if (dataCount % 100 == 0)
                    System.err.print(dataCount + " ... ");

                Pair<Configuration, Integer> configurationIntegerPair = pool.take().get();
                confs[configurationIntegerPair.second] = configurationIntegerPair.first;
            }

            for (int j = 0; j < confs.length; j++) {
                Configuration bestParse = confs[j];

                int[] words = data.get(j).getSentence().getWords();

                allArcs += words.length - 1;

                StringBuilder finalOutput = new StringBuilder();
                for (int i = 0; i < words.length; i++) {
                    int w = i + 1;
                    int head = bestParse.state.getHead(w);
                    int dep = bestParse.state.getDependency(w);

                    if (w == bestParse.state.rootIndex && !rootFirst)
                        continue;

                    if (head == bestParse.state.rootIndex)
                        head = 0;

                    String label=head==0 ? maps.rootString : maps.revWords[dep];
                    String output = head + "\t" + label + "\n";
                    finalOutput.append(output);
                }
                finalOutput.append("\n");
                writer.write(finalOutput.toString());
            }
        }

        System.err.print("\n");
        long end = System.currentTimeMillis();
        float each = (1.0f * (end - start)) / size;
        float eacharc = (1.0f * (end - start)) / allArcs;

        writer.flush();
        writer.close();

        DecimalFormat format = new DecimalFormat("##.00");

        System.err.print(format.format(eacharc) + " ms for each arc!\n");
        System.err.print(format.format(each) + " ms for each sentence!\n\n");

        BufferedReader gReader = new BufferedReader(new FileReader(inputFile));
        BufferedReader pReader = new BufferedReader(new FileReader(outputFile + ".tmp"));
        BufferedWriter pwriter = new BufferedWriter(new FileWriter(outputFile));

        String line;

        while ((line = pReader.readLine()) != null) {
            String gLine = gReader.readLine();
            if (line.trim().length() > 0) {
                while (gLine.trim().length() == 0)
                    gLine = gReader.readLine();
                String[] ps = line.split("\t");
                String[] gs = gLine.split("\t");
                gs[6] = ps[0];
                gs[7] = ps[1];
                StringBuilder output = new StringBuilder();
                for (int i = 0; i < gs.length; i++) {
                    output.append(gs[i]).append("\t");
                }
                pwriter.write(output.toString().trim() + "\n");
            } else {
                pwriter.write("\n");
            }
        }
        pwriter.flush();
        pwriter.close();
    }

    public void shutDownLiveThreads() {
        boolean isTerminated = executor.isTerminated();
        while (!isTerminated) {
            executor.shutdownNow();
            isTerminated = executor.isTerminated();
        }
    }
}