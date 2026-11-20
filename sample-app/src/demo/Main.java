package demo;

public class Main {
    public static void main(String[] args) {
        UserService service = new UserService();
        System.out.println("name  = " + service.getName());
        System.out.println("age   = " + service.getAge(7L));
        System.out.println("vip   = " + service.isVip());
        service.log("hello");
    }
}
