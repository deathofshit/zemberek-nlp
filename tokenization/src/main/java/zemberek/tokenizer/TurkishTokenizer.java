package zemberek.tokenizer;


import com.google.common.io.Resources;
import zemberek.core.collections.FixedBitVector;
import zemberek.core.collections.FloatValueMap;
import zemberek.core.collections.UIntSet;
import zemberek.core.io.IOUtil;
import zemberek.core.logging.Log;
import zemberek.core.text.TextUtil;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Extracts tokens from sentences.
 * TODO: finish this.
 */
class TurkishTokenizer extends PerceptronSegmenter implements Tokenizer {

    private FloatValueMap<String> weights = new FloatValueMap<>();

    public static TurkishTokenizer fromInternalModel() throws IOException {
        try (DataInputStream dis = IOUtil.getDataInputStream(
                Resources.getResource("tokenizer/sentence-boundary-model.bin").openStream())) {
            return new TurkishTokenizer(load(dis));
        }
    }

    private TurkishTokenizer(FloatValueMap<String> weights) {
        this.weights = weights;
    }

    static class Span {

        final int start;
        final int end;

        public Span(int start, int end) {
            this.start = start;
            this.end = end;
        }
    }

    private static Locale tr = new Locale("tr");

    private static final String TurkishLowerCase = "abcçdefgğhıijklmnoöprsştuüvyzxwq";
    private static final String TurkishUpperCase = TurkishLowerCase.toUpperCase(tr);

    private static final String boundaryDesicionChars = "'+-./:@&";

    private static final String singleTokenChars = "!\"#$%()*+,-./:;<=>?@[\\]^_{|}~¡¢£¤¥¦§¨©ª«¬®¯" +
            "°±²³´µ¶·¸¹º»¼½¾¿";

    private static FixedBitVector singleTokenLookup = generateBitLookup(singleTokenChars);
    private static FixedBitVector boundaryDesicionLookup = generateBitLookup(boundaryDesicionChars);

    private static FixedBitVector generateBitLookup(String characters) {

        int max = 0;
        for (char c : characters.toCharArray()) {
            if (c > max) {
                max = c;
            }
        }

        FixedBitVector result = new FixedBitVector(max + 1);
        for (char c : characters.toCharArray()) {
            result.set(c);
        }

        return result;
    }

    private List<Span> tokenizeSpan(String sentence) {
        List<Span> tokens = new ArrayList<>();

        int tokenBegin = 0;

        for (int j = 0; j < sentence.length(); j++) {
            // skip if char cannot be a boundary char.
            char chr = sentence.charAt(j);
            if (Character.isWhitespace(chr)) {
                if (tokenBegin < j) {
                    tokens.add(new Span(tokenBegin, j));
                }
                tokenBegin = j;
                continue;
            }
            if (chr < singleTokenLookup.length && singleTokenLookup.get(chr)) {
                // add previous token if available.
                if (tokenBegin < j) {
                    tokens.add(new Span(tokenBegin, j));
                }
                // add single symbol token.
                tokens.add(new Span(j, j + 1));
                tokenBegin = j + 1;
                continue;
            }
            if (chr < boundaryDesicionLookup.length && boundaryDesicionLookup.get(chr)) {
                // TODO: make it work.
                if (weights.get("foo") < 0) {
                    if (tokenBegin < j) {
                        tokens.add(new Span(tokenBegin, j));
                    }
                    tokenBegin = j;
                }
            }
        }
        // add remaining token.
        if (tokenBegin < sentence.length()) {
            tokens.add(new Span(tokenBegin, sentence.length()));
        }
        return tokens;
    }

    private List<String> tokenize(String sentence) {
        List<String> tokens = new ArrayList<>();
        List<Span> spans = tokenizeSpan(sentence);
        for (Span span : spans) {
            tokens.add(sentence.substring(span.start, span.end));
        }
        return tokens;
    }


    @Override
    public List<String> tokenStrings(String input) {
        return tokenize(input);
    }

    public static class TrainerBuilder {
        Path trainFile;
        int iterationCount = 5;
        int skipSpaceFrequency = 20;
        int lowerCaseFirstLetterFrequency = 20;
        boolean shuffleInput = false;

        public TrainerBuilder(Path trainFile) {
            this.trainFile = trainFile;
        }

        public TrainerBuilder iterationCount(int count) {
            this.iterationCount = count;
            return this;
        }

        public TrainerBuilder shuffleSentences() {
            this.shuffleInput = true;
            return this;
        }

        public TrainerBuilder skipSpaceFrequencyonCount(int count) {
            this.skipSpaceFrequency = skipSpaceFrequency;
            return this;
        }

        public TrainerBuilder lowerCaseFirstLetterFrequency(int count) {
            this.lowerCaseFirstLetterFrequency = lowerCaseFirstLetterFrequency;
            return this;
        }

        public Trainer build() {
            return new Trainer(this);
        }
    }


    public static class Trainer {
        private TrainerBuilder builder;

        public static TrainerBuilder builder(Path trainFile) {
            return new TrainerBuilder(trainFile);
        }

        private Trainer(TrainerBuilder builder) {
            this.builder = builder;
        }

        private static Locale Turkish = new Locale("tr");

        public TurkishTokenizer train() throws IOException {
            FloatValueMap<String> weights = new FloatValueMap<>();
            List<String> sentences = TextUtil.loadLinesWithText(builder.trainFile);
            FloatValueMap<String> averages = new FloatValueMap<>();

            int updateCount = 0;

            for (int i = 0; i < builder.iterationCount; i++) {

                Log.info("Iteration = %d", i + 1);

                if (builder.shuffleInput) {
                    Collections.shuffle(sentences);
                }

                for (String sentenceWithPipe : sentences) {

                    UIntSet indexSet = new UIntSet();


                    if (sentenceWithPipe.trim().length() == 0) {
                        continue;
                    }

                    int indexCounter = 0;
                    for (int j = 0; j < sentenceWithPipe.length(); j++) {
                        char chr = sentenceWithPipe.charAt(j);
                        if (chr == '|') {
                            indexSet.add(indexCounter);
                        } else {
                            indexCounter++;
                        }
                    }

                    String sentence = sentenceWithPipe.replaceAll("\\|","");

                    for (int j = 0; j < sentence.length(); j++) {
                        // skip if char cannot be a boundary char.
                        char chr = sentence.charAt(j);
                        if (chr < boundaryDesicionLookup.length && boundaryDesicionLookup.get(chr)) {
                            continue;
                        }

                        BoundaryData boundaryData = new BoundaryData(sentence, j);
                        if (boundaryData.nonBoundaryCheck()) {
                            continue;
                        }
                        List<String> features = boundaryData.extractFeatures();
                        float score = 0;
                        for (String feature : features) {
                            score += weights.get(feature);
                        }
                        int update = 0;
                        // if we found no-boundary but it is a boundary
                        if (score <= 0 && indexSet.contains(j)) {
                            update = 1;
                        }
                        // if we found boundary but it is not a boundary
                        else if (score > 0 && !indexSet.contains(j)) {
                            update = -1;
                        }
                        updateCount++;
                        if (update != 0) {
                            for (String feature : features) {
                                double d = weights.incrementByAmount(feature, update);
                                if (d == 0.0) {
                                    weights.remove(feature);
                                }
                                d = averages.incrementByAmount(feature, updateCount * update);
                                if (d == 0.0) {
                                    averages.remove(feature);
                                }
                            }
                        }
                    }
                }
            }
            for (String key : weights) {
                weights.set(key, weights.get(key) - averages.get(key) * 1f / updateCount);
            }

            return new TurkishTokenizer(weights);
        }

    }

    static class BoundaryData {
        char currentChar;
        char previousLetter;
        char nextLetter;
        String previousTwoLetters;
        String nextTwoLetters;

        BoundaryData(String input, int pointer) {

            previousLetter = pointer > 0 ? input.charAt(pointer - 1) : '_';
            nextLetter = pointer < input.length() - 1 ? nextLetter = input.charAt(pointer + 1) : '_';
            previousTwoLetters = pointer > 2 ? input.substring(pointer - 2, pointer) : "__";
            nextTwoLetters = pointer < input.length() - 3 ? input.substring(pointer + 1, pointer + 3) : "__";
            currentChar = input.charAt(pointer);
        }


        boolean nonBoundaryCheck() {
            return false;
        }

        List<String> extractFeatures() {
            List<String> features = new ArrayList<>();
            features.add("1:" + Character.isUpperCase(previousLetter));
            features.add("1b:" + Character.isWhitespace(nextLetter));
            features.add("1a:" + previousLetter);
            features.add("1b:" + nextLetter);
            features.add("2p:" + previousTwoLetters);
            features.add("2n:" + nextTwoLetters);
            return features;
        }
    }


}
