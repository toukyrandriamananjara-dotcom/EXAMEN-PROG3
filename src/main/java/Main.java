package main.java;

public class Main {
    public static void main(String[] args) {
        DataRetriever dataRetriever = new DataRetriever();
        Dish saladeVerte = dataRetriever.findDishById(1);
        System.out.println(saladeVerte);

    }
}
