package jspectrumanalyzer.core;

public class Ranges {

    private int freqStart;
    private int freqStop;
    private String freqRange;
    private boolean isMultiple;

    public Ranges(String input) {
        this.freqRange = input;
        parse(input);
    }

    private void parse(String input) {
        String[] intervals = input.split(",");
        isMultiple = intervals.length > 1;

        try {
            if (isMultiple) {
                // Prvý interval
                String[] first = intervals[0].split("-");
                freqStart = Integer.parseInt(first[0].trim());

                // Posledný interval
                String[] last = intervals[intervals.length - 1].split("-");
                freqStop = Integer.parseInt(last[1].trim());
            } else {
                // Len jeden interval
                String[] parts = intervals[0].split("-");
                freqStart = Integer.parseInt(parts[0].trim());
                freqStop = Integer.parseInt(parts[1].trim());
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("Neplatný formát vstupu: " + input);
        }
    }

    public int getFreqStart() {
        return freqStart;
    }

    public int getFreqStop() {
        return freqStop;
    }

    public String getFreqRange() {
        return freqRange;
    }

    public boolean isMultipleRanges() {
        return isMultiple;
    }
}
