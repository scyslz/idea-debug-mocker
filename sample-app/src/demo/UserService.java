package demo;

public class UserService {

    public String getName() {
        return "real-name";
    }

    public int getAge(Long id) {
        return 42;
    }

    public boolean isVip() {
        return false;
    }

    public void log(String message) {
        System.out.println("REAL log(): " + message);
    }
}
