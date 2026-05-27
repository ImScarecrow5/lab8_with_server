module org.example.lab88_java {
    requires javafx.controls;
    requires javafx.fxml;
    requires java.desktop;


    opens org.example.lab88_java to javafx.fxml;
    exports org.example.lab88_java;
}