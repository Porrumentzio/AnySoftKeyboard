import static java.lang.Long.toUnsignedString;
import static java.lang.Math.log;
import static java.lang.Math.round;
import static org.gradle.api.tasks.PathSensitivity.RELATIVE;

import static java.lang.Long.signum;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Scanner;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.gradle.api.DefaultTask;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.TaskAction;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

/** Task to merge several word-list files into one */
@CacheableTask
public class MergeWordsListTask extends DefaultTask {
    @TaskAction
    public void mergeWordsLists() throws IOException, ParserConfigurationException, SAXException {
        if (inputWordsListFiles == null || inputWordsListFiles.length == 0) {
            throw new IllegalArgumentException("Must specify at least one inputWordsListFiles");
        }
        if (outputWordsListFile == null) {
            throw new IllegalArgumentException("Must supply outputWordsListFile");
        }

        System.out.println(
                "Merging "
                        + inputWordsListFiles.length
                        + " files for maximum "
                        + maxWordsInList
                        + " words, and writing into \'"
                        + outputWordsListFile.getName()
                        + "\', discarding words from \'"
                        + discardListFile.getName()
                        + "\'.");

        final HashMap<String, WordWithCount> allWords = new HashMap<>();

        for (File inputFile : inputWordsListFiles) {
            System.out.println("Reading " + inputFile.getName() + "...");
            if (!inputFile.exists()) throw new FileNotFoundException(inputFile.getAbsolutePath());
            SAXParserFactory parserFactory = SAXParserFactory.newInstance();
            SAXParser parser = parserFactory.newSAXParser();
            final InputStreamReader inputStream =
                    new InputStreamReader(new FileInputStream(inputFile), Charset.forName("UTF-8"));
            InputSource inputSource = new InputSource(inputStream);
            parser.parse(inputSource, new MySaxHandler(allWords));
            System.out.println("Loaded " + allWords.size() + " words in total...");
            inputStream.close();
            System.out.println("Closed " + inputFile.getName());
        }

        // discarding unwanted words
        if (discardListFile != null) {
            if (!discardListFile.exists()) throw new FileNotFoundException(discardListFile.getAbsolutePath());
            System.out.print("Discarding words...");
            ArrayList<String> discardList = new ArrayList<String>();
            Scanner scanner = new Scanner(discardListFile);
            String line;
            while (scanner.hasNextLine()) {
                line = scanner.nextLine();
                if (!line.isEmpty())
                    discardList.add(line);
            }
            discardList.stream()
                    .forEach(
                            word -> {
                                if (allWords.remove(word) != null) System.out.print(".");
                            });
            System.out.println();
        }

        System.out.println("Creating output XML file...");
        try (WordListWriter writer = new WordListWriter(outputWordsListFile)) {
            allWords.values()
                    .forEach(
                            word ->
                                    WordListWriter.writeWordWithRuntimeException(
                                            writer, word.getWord(), word.getFreq(), word.getFreqAbs()));
            System.out.println("Done.");
        }

        // sort allWords by frequency (descending)
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder;
        Document doc = null;
        try {
            builder = factory.newDocumentBuilder();
            doc = builder.parse(outputWordsListFile);
        }
        catch (Exception e) {
            System.out.println("Failed to create new DocumentBuilder: " + e);
        }
        List<Node> newWNodes = new ArrayList<>();
        Node root = doc.getElementsByTagName("wordlist").item(0);
        NodeList nodeList = doc.getElementsByTagName("w");
        long counter = 0;
        for (int i=0; i<nodeList.getLength(); ++i) {
            newWNodes.add(nodeList.item(i));
            counter += Long.parseLong(nodeList.item(i).getAttributes().getNamedItem("abs").getNodeValue());
        }
        Node[] ret = new Node[newWNodes.size()];
        ret = newWNodes.toArray(ret);
        Arrays.sort(ret, (n1, n2) ->  signum(Long.parseLong(n2.getAttributes().getNamedItem("abs").getNodeValue())
                                              - Long.parseLong(n1.getAttributes().getNamedItem("abs").getNodeValue())));

        Node newwordlist = root.cloneNode(false);
        //root.getParentNode().replaceChild(newwordlist, root);
        double maxabsfreq = 0;
        double check = 0.0;
        int mostfreq = 100;
        for (int i=0; i<ret.length; ++i) {
            //System.out.println(i + "th item: " + ret[i].getAttributes().toString());
            double absfreq = Double.parseDouble(ret[i].getAttributes().getNamedItem("abs").getNodeValue());
            check += absfreq/counter;
            // int relfreq = 1+(int) round(absfreq/counter*255);
            int relfreq = 1+(int) round(log(absfreq)/log(counter)*255);
            if (relfreq > 255) relfreq = 255;
            ret[i].getAttributes().getNamedItem("f").setNodeValue(Integer.toString(relfreq));
            newwordlist.appendChild(ret[i]);
            /* if (mostfreq >= 0) {
                System.out.println("DOS: abs=" + ret[i].getAttributes().getNamedItem("abs").getNodeValue()
                        + " rel=" + ret[i].getAttributes().getNamedItem("f").getNodeValue()
                        + " word=" + ret[i].getFirstChild().getNodeValue());
                mostfreq--;
            } */
        }
        // System.out.println("DOS: check = " + check);

        try {
            Transformer transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setParameter(OutputKeys.INDENT, "yes");
            StreamResult result = new StreamResult(new FileWriter(outputWordsListFile));
            DOMSource source = new DOMSource(newwordlist);
            transformer.transform(source, result);
        }
        catch (Exception e) {
            System.out.print("Exception while writing out new XML: " + e);
        }

        System.out.println("Merge complete");
    }

    @InputFiles
    @PathSensitive(RELATIVE)
    public File[] getInputWordsListFiles() {
        return inputWordsListFiles;
    }

    public void setInputWordsListFiles(File[] inputWordsListFiles) {
        this.inputWordsListFiles = inputWordsListFiles;
    }

    @OutputFile
    public File getOutputWordsListFile() {
        return outputWordsListFile;
    }

    public void setOutputWordsListFile(File outputWordsListFile) {
        this.outputWordsListFile = outputWordsListFile;
    }

    @InputFiles
    @PathSensitive(RELATIVE)
    public File getDiscardListFile() {
        return discardListFile;
    }

    public void setDiscardListFile(File discardListFile) {
        this.discardListFile = discardListFile;
    }

    @Input
    public int getMaxWordsInList() {
        return maxWordsInList;
    }

    public void setMaxWordsInList(int maxWordsInList) {
        this.maxWordsInList = maxWordsInList;
    }

    private File[] inputWordsListFiles;
    private File outputWordsListFile;
    private File discardListFile;
    private int maxWordsInList = Integer.MAX_VALUE;

    private static class MySaxHandler extends DefaultHandler {

        private HashMap<String, WordWithCount> allWords;
        private boolean inWord;
        private StringBuilder word = new StringBuilder();
        private int freq;
        private long freqabs;

        public MySaxHandler(HashMap<String, WordWithCount> allWords) {
            this.allWords = allWords;
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes)
                throws SAXException {
            super.startElement(uri, localName, qName, attributes);
            if (qName.equals("w")) {
                inWord = true;
                freq = Integer.parseInt(attributes.getValue("f"));
                freqabs = Long.parseLong(attributes.getValue("abs"));
                word.setLength(0);
            } else {
                inWord = false;
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) throws SAXException {
            super.characters(ch, start, length);
            if (inWord) {
                word.append(ch, start, length);
            }
        }

        @Override
        public void skippedEntity(String name) throws SAXException {
            System.out.print("Skipped " + name);
            super.skippedEntity(name);
        }

        @Override
        public void warning(SAXParseException e) throws SAXException {
            System.out.print("Warning! " + e);
            super.warning(e);
        }

        @Override
        public void error(SAXParseException e) throws SAXException {
            System.out.print("Error! " + e);
            super.error(e);
        }

        @Override
        public void fatalError(SAXParseException e) throws SAXException {
            System.out.print("Fatal-Error! " + e);
            super.fatalError(e);
        }

        @Override
        public void unparsedEntityDecl(
                String name, String publicId, String systemId, String notationName)
                throws SAXException {
            System.out.print("unparsedEntityDecl! " + name);
            super.unparsedEntityDecl(name, publicId, systemId, notationName);
        }

        @Override
        public void endElement(String uri, String localName, String qName) throws SAXException {
            super.endElement(uri, localName, qName);
            if (qName.equals("w") && inWord) {
                WordWithCount wordWithCount = new WordWithCount(word.toString(), freq, freqabs);
                if (allWords.containsKey(wordWithCount.getKey())) {
                    allWords.get(wordWithCount.getKey()).addOtherWord(wordWithCount);
                } else {
                    allWords.put(wordWithCount.getKey(), wordWithCount);
                }
            }

            inWord = false;
        }
    }
}
